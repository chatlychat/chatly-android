package com.chatly

import android.content.Context
import android.content.Intent
import com.chatly.models.Attachment
import com.chatly.models.ChatlyIdentity
import com.chatly.models.ConversationListResponse
import com.chatly.models.ConversationSummary
import com.chatly.models.InitResponse
import com.chatly.models.KbArticle
import com.chatly.models.KbArticleResponse
import com.chatly.models.KbResult
import com.chatly.models.KbSearchResponse
import com.chatly.models.MessageListResponse
import com.chatly.models.MessageRow
import com.chatly.models.StartResponse
import com.chatly.net.HttpTransport
import com.chatly.net.HttpUrlConnectionTransport
import com.chatly.realtime.IoRealtimeSocketFactory
import com.chatly.realtime.RealtimeEnvelope
import com.chatly.realtime.RealtimeSocketFactory
import com.chatly.realtime.SocketTransport
import com.chatly.storage.InMemoryVisitorTokenStorage
import com.chatly.storage.TokenStore
import com.chatly.storage.VisitorTokenStorage
import com.chatly.ui.ChatlyActivity
import com.chatly.ui.theme.ChatlyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Production. A self-hosted install passes its own to [Chatly.configure]. */
public const val DEFAULT_BASE_URL: String = "https://api.chatlychat.com"

/**
 * Chatly Android SDK (Kotlin).
 *
 * ── Every request on this client used to be a 400 ──────────────────────────
 *
 * The widget surface reads `publicId` and `visitorToken` **from the JSON
 * body** — it has never read an `Authorization` header. This client sent
 * `channelPublicId` on `init`, sent neither field on `track`, `send` or
 * `registerFcmToken`, put the token it parsed into a bearer header, and read
 * a top-level `sessionToken` where `init` answers `session.token`. zod STRIPS
 * an unknown key rather than refusing it, so `channelPublicId` was not an
 * error — it was simply absent, and `publicId` was then missing, which is the
 * 400 the visitor actually got. Each body below is written against the schema
 * in `apps/api/src/modules/widget/widget.schemas.ts` and checked by
 * `src/test/kotlin/com/chatly/RequestShapeTest.kt`, which asserts on what the
 * SDK SENT rather than on a response a fake transport invented.
 *
 * ── Construction ───────────────────────────────────────────────────────────
 *
 * [configure] builds the process-wide singleton; [shared] returns it or
 * throws. Passing a `Context` is optional and only decides where the visitor
 * session is kept: with one it goes to [TokenStore] (EncryptedSharedPreferences,
 * so history survives a relaunch), without one it lives in memory for the life
 * of the process.
 */
public class Chatly internal constructor(
    public val channelPublicId: String,
    public val baseUrl: String,
    private val transport: HttpTransport,
    internal val storage: VisitorTokenStorage,
    private val socketFactory: RealtimeSocketFactory,
    private val realtimeUrlOverride: String? = null,
    private val reconnectDelayMs: Long = 1_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Palette applied by every bundled UI surface. Set it before presenting. */
    public var theme: ChatlyTheme = ChatlyTheme()

    internal val json = Json { ignoreUnknownKeys = true }

    /** `realtimeUrl` as `init` reported it. Null until the first init. */
    @Volatile private var serverRealtimeUrl: String? = null

    /**
     * `session.contactId` from the last init.
     *
     * Needed by [markRead]: the gateway requires the reader to identify
     * themselves and refuses a receipt whose `readerId` is not the socket's
     * own contact. Nothing kept it before, which is part of why the read
     * receipt could not be built correctly.
     */
    @Volatile private var sessionContactId: String? = null

    /**
     * The thread this client is pointed at. Set by [startConversation] and
     * [openConversation]; [send] falls back to opening a new one.
     */
    @Volatile private var activeConversationId: String? = null

    private val lock = Any()
    private var realtime: SocketTransport? = null
    private var pump: Job? = null
    private var boundConversationId: String? = null

    /**
     * One stable flow across socket rebuilds.
     *
     * The realtime token is conversation-SCOPED, so switching conversations
     * means a new token and a new socket. A caller that collected the
     * transport's own flow would stop receiving at the first switch, and the
     * bundled fragment collects once in `onResume` — before any conversation
     * exists. So envelopes are pumped into this instead.
     */
    private val _events = MutableSharedFlow<RealtimeEnvelope>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    public companion object {
        @Volatile private var instance: Chatly? = null

        /**
         * Build the singleton. The first call wins; later calls return it.
         *
         * @param context optional — when given, the visitor session is
         *   persisted with [TokenStore] so a relaunch keeps its history.
         * @param realtimeUrl optional override for single-origin deployments.
         *   Normally left alone: `init` reports where to connect.
         */
        @JvmStatic
        @JvmOverloads
        public fun configure(
            channelPublicId: String,
            baseUrl: String = DEFAULT_BASE_URL,
            context: Context? = null,
            realtimeUrl: String? = null,
        ): Chatly {
            return instance ?: synchronized(this) {
                instance ?: Chatly(
                    channelPublicId = channelPublicId,
                    baseUrl = baseUrl.trimEnd('/'),
                    transport = HttpUrlConnectionTransport(),
                    storage = if (context == null) {
                        InMemoryVisitorTokenStorage()
                    } else {
                        TokenStore.create(context, channelPublicId)
                    },
                    socketFactory = IoRealtimeSocketFactory(),
                    realtimeUrlOverride = realtimeUrl,
                ).also { instance = it }
            }
        }

        @JvmStatic
        public fun shared(): Chatly =
            instance ?: throw IllegalStateException("Chatly.configure() not called")
    }

    // ───────────────────────── session ─────────────────────────

    /**
     * `POST /v1/widget/init` — open or resume the visitor session.
     *
     * The token comes back at `session.token`, NOT at the top level, and it is
     * persisted so the next launch resumes the same visitor rather than
     * minting a stranger.
     */
    public suspend fun initSession(identity: ChatlyIdentity? = null): InitResponse =
        init(identity?.let { JSONObject(json.encodeToString(ChatlyIdentity.serializer(), it)) })

    /**
     * Identify the person using the app.
     *
     * Posts to `/v1/widget/init`: there has never been a `/v1/widget/identify`
     * route, and `init` is where the identity hash is checked. The identity
     * travels as a NESTED `identity` object — this used to send `externalId`,
     * `email`, `name` and `attributes` as top-level keys, every one of which
     * zod stripped, so the call registered no identity at all while answering
     * 200.
     *
     * `userHash` is `HMAC-SHA256(<channel IDV secret>, externalId)`, computed
     * on your backend. Never in the app: a secret in an APK is a secret a
     * stranger has.
     *
     * Either `externalId` or `email` is enough. An email-only claim can never
     * be VERIFIED — `userHash` binds the external id alone — so it leaves the
     * session unverified, which is accepted on any channel.
     */
    public suspend fun identify(
        externalId: String? = null,
        email: String? = null,
        name: String? = null,
        userHash: String? = null,
        attributes: Map<String, Any?> = emptyMap(),
    ): InitResponse {
        return init(
            JSONObject().apply {
                putIfNotNull("externalId", externalId)
                putIfNotNull("email", email)
                putIfNotNull("name", name)
                putIfNotNull("userHash", userHash)
                if (attributes.isNotEmpty()) put("attributes", JSONObject(attributes))
            },
        )
    }

    /**
     * The one `POST /v1/widget/init`, with [identity] already shaped.
     *
     * `widgetInitSchema` reads a NESTED `identity` object. Both callers go
     * through here so the two cannot drift — the flattened body that
     * registered nobody was a second copy of this that had been edited once.
     */
    private suspend fun init(identity: JSONObject?): InitResponse {
        val body = JSONObject().apply {
            put("publicId", channelPublicId)
            putIfNotNull("visitorToken", storage.read())
            put("locale", java.util.Locale.getDefault().toLanguageTag())
            put("timezone", java.util.TimeZone.getDefault().id)
            putIfNotNull("identity", identity)
        }
        val parsed = json.decodeFromString(InitResponse.serializer(), post("/v1/widget/init", body))
        storage.write(parsed.session.token)
        serverRealtimeUrl = parsed.realtimeUrl
        sessionContactId = parsed.session.contactId
        return parsed
    }

    /** Forget this device's session. The next call inits a new visitor. */
    public fun logout() {
        disconnectRealtime()
        activeConversationId = null
        sessionContactId = null
        storage.wipe()
    }

    // ───────────────────────── events + push ─────────────────────────

    /** `POST /v1/widget/events` — a CDP event. Feeds audiences and triggers. */
    public suspend fun track(name: String, properties: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val body = JSONObject().apply {
            put("publicId", channelPublicId)
            put("visitorToken", ensureToken())
            put("name", name)
            if (properties.isNotEmpty()) put("properties", JSONObject(properties))
        }
        return JSONObject(post("/v1/widget/events", body)).toMap()
    }

    /**
     * `POST /v1/widget/push/register` as `platform: "fcm"`.
     *
     * The agent-side `/v1/push-devices` surface is unrelated — visitor push
     * registers through the widget.
     */
    public suspend fun registerFcmToken(token: String, appVersion: String? = null): Map<String, Any?> {
        val body = JSONObject().apply {
            put("publicId", channelPublicId)
            put("visitorToken", ensureToken())
            put("platform", "fcm")
            put("token", token)
            putIfNotNull("appVersion", appVersion)
        }
        return JSONObject(post("/v1/widget/push/register", body)).toMap()
    }

    // ───────────────────────── conversations ─────────────────────────

    /** The thread this client is currently pointed at, if any. */
    public fun currentConversationId(): String? = activeConversationId

    /** `POST /v1/widget/conversations/list` — the visitor's own threads. */
    public suspend fun listConversations(limit: Int = 20): List<ConversationSummary> {
        val body = JSONObject().apply {
            put("publicId", channelPublicId)
            put("visitorToken", ensureToken())
            put("limit", limit)
        }
        return json.decodeFromString(
            ConversationListResponse.serializer(),
            post("/v1/widget/conversations/list", body),
        ).conversations
    }

    /**
     * `POST /v1/widget/conversations/{id}/read` — persist how far the visitor
     * has read.
     *
     * The DURABLE half of a read receipt, and none of the four mobile SDKs
     * had it: only the web widget ever called this route. Every one of them
     * renders the unread badge from the server's own `unreadContactCount`,
     * so without this the count could only go UP — it survived app restarts,
     * and the agent inbox never learned a native visitor had seen the
     * thread.
     *
     * Distinct from [markRead], which emits the realtime "Read ✓" tick over
     * the socket. That one is advisory and vanishes with the connection; this
     * one is the row. An app wanting both behaviours calls both, and calling
     * only this one is the safer half to have.
     *
     * `upToMessageId` is optional server-side: omitting it means "I have read
     * this thread as of now", which is what an app that does not track
     * per-message visibility should send.
     */
    public suspend fun persistRead(upToMessageId: String? = null): Boolean {
        val conversationId = activeConversationId ?: return false
        val body = JSONObject().apply {
            put("publicId", channelPublicId)
            put("visitorToken", ensureToken())
            putIfNotNull("upToMessageId", upToMessageId)
        }
        post("/v1/widget/conversations/$conversationId/read", body)
        return true
    }

    /** `POST /v1/widget/messages/list` — one thread's history. */
    public suspend fun listMessages(conversationId: String, limit: Int = 50): List<MessageRow> {
        val body = JSONObject().apply {
            put("publicId", channelPublicId)
            put("visitorToken", ensureToken())
            put("conversationId", conversationId)
            put("limit", limit)
        }
        return json.decodeFromString(
            MessageListResponse.serializer(),
            post("/v1/widget/messages/list", body),
        ).messages
    }

    /**
     * `POST /v1/widget/conversations` — open a thread with a first message.
     *
     * Binds realtime to the new conversation before returning, so an agent's
     * reply arrives live rather than on the next manual refresh.
     */
    public suspend fun startConversation(
        body: String,
        attachments: List<Attachment> = emptyList(),
    ): StartResponse {
        val payload = JSONObject().apply {
            put("publicId", channelPublicId)
            put("visitorToken", ensureToken())
            put("body", body)
            if (attachments.isNotEmpty()) put("attachments", attachments.toJsonArray())
        }
        val started = json.decodeFromString(
            StartResponse.serializer(),
            post("/v1/widget/conversations", payload),
        )
        openConversation(started.conversation.id)
        return started
    }

    /** `POST /v1/widget/messages` — reply on an existing thread. */
    public suspend fun replyToConversation(
        conversationId: String,
        body: String,
        attachments: List<Attachment> = emptyList(),
    ): MessageRow {
        val payload = JSONObject().apply {
            put("publicId", channelPublicId)
            put("visitorToken", ensureToken())
            put("conversationId", conversationId)
            put("body", body)
            if (attachments.isNotEmpty()) put("attachments", attachments.toJsonArray())
        }
        return json.decodeFromString(MessageRow.serializer(), post("/v1/widget/messages", payload))
    }

    /**
     * Send a message, opening a thread if there isn't one.
     *
     * `POST /v1/widget/messages` REQUIRES a `conversationId`, and this method
     * used to send a body alone with no `publicId` and no `visitorToken` —
     * three missing required fields on one call. It now starts a conversation
     * when there is none and replies on the active one when there is.
     */
    public suspend fun send(message: String, attachments: List<Attachment> = emptyList()): Map<String, Any?> {
        val existing = activeConversationId
        if (existing == null) {
            val started = startConversation(message, attachments)
            return mapOf(
                "conversationId" to started.conversation.id,
                "messageId" to started.message.id,
            )
        }
        val row = replyToConversation(existing, message, attachments)
        return mapOf("conversationId" to existing, "messageId" to row.id)
    }

    // ───────────────────────── uploads ─────────────────────────

    /**
     * Three steps, and the middle one is raw bytes.
     *
     * `POST /v1/widget/uploads/presign` takes **`contentType`**, and the
     * attachment that goes onto the message calls the same string
     * **`mimeType`**. An SDK that sends `mimeType` to the presign route has
     * the key dropped by zod and gets a 400 on the missing `contentType` —
     * which is what the React Native and Flutter clients do today. The upload
     * itself is the bytes, with every header the presign response returned:
     * not multipart, not base64.
     *
     * The route refuses before presigning when attachments are off for the
     * channel, when the MIME type is outside `WIDGET_UPLOAD_MIME`, or when the
     * file is over the channel's cap. That copy is worth surfacing verbatim,
     * so it travels in the thrown message unchanged.
     */
    public suspend fun uploadAttachment(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
    ): Attachment {
        val presignBody = JSONObject().apply {
            put("publicId", channelPublicId)
            put("visitorToken", ensureToken())
            put("filename", filename)
            put("contentType", mimeType)
            put("sizeBytes", bytes.size)
        }
        val presigned = JSONObject(post("/v1/widget/uploads/presign", presignBody))
        val headers = mutableMapOf<String, String>()
        presigned.optJSONObject("headers")?.let { h ->
            for (key in h.keys()) headers[key] = h.get(key).toString()
        }
        val put = transport.uploadBytes(
            url = presigned.getString("url"),
            method = presigned.optString("method", "PUT"),
            headers = headers,
            bytes = bytes,
        )
        if (!put.ok) {
            throw RuntimeException("chatly upload ${put.status}: ${put.body}")
        }
        return Attachment(
            id = presigned.getString("uploadId"),
            url = presigned.getString("publicUrl"),
            filename = filename,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
        )
    }

    // ───────────────────────── realtime ─────────────────────────

    /**
     * Inbound envelopes for whichever conversation is open.
     *
     * Safe to collect before one is: the flow is stable for the life of the
     * client and the socket is attached underneath it by [openConversation].
     */
    public fun realtimeEvents(): SharedFlow<RealtimeEnvelope> = _events.asSharedFlow()

    /**
     * Point the client — and the socket — at one conversation.
     *
     * A realtime token names ONE conversation, so switching means minting a
     * new token and reconnecting. Reusing the socket across a switch delivers
     * the old conversation's events.
     */
    public suspend fun openConversation(conversationId: String) {
        activeConversationId = conversationId
        val alreadyBound = synchronized(lock) { boundConversationId == conversationId && realtime != null }
        if (alreadyBound) return
        bindRealtime(conversationId)
    }

    /**
     * Report a read receipt for the open conversation.
     *
     * This sent `{"conversationId","messageId"}` and was a SILENT NO-OP on
     * every installed Android app. `messageReadPayloadSchema` names the
     * anchor `upToMessageId`, and requires `readAt`, `readerType` and
     * `readerId` besides — so `safeParse` failed at the gateway and the
     * handler returned without a word. The agent never saw the "Read" tick
     * and the visitor's badge never cleared, with no error anywhere.
     *
     * `upToMessageId` is optional server-side, which is exactly why the
     * wrong name was survivable-looking rather than loud: supply the other
     * four correctly and a misnamed anchor parses fine and is dropped.
     *
     * Returns false when the receipt could not be sent, rather than failing
     * silently as before — there is no conversation open, no socket, or no
     * session to name as the reader.
     */
    public fun markRead(upToMessageId: String? = null): Boolean {
        val conversationId = activeConversationId ?: return false
        val contactId = sessionContactId ?: return false
        val transport = synchronized(lock) { realtime } ?: return false
        transport.emitRead(conversationId, contactId, upToMessageId)
        return true
    }

    /** Close the socket and stop reconnecting. */
    public fun disconnectRealtime() {
        val (transportToClose, pumpToCancel) = synchronized(lock) {
            val pair = realtime to pump
            realtime = null
            pump = null
            boundConversationId = null
            pair
        }
        pumpToCancel?.cancel()
        transportToClose?.disconnect()
    }

    private suspend fun bindRealtime(conversationId: String) {
        disconnectRealtime()
        val socket = SocketTransport(
            url = resolveRealtimeUrl(),
            // Re-invoked per connect ATTEMPT. The token is conversation-scoped
            // with a 1h TTL and the Java socket.io client has no per-attempt
            // auth hook, so a credential captured once is a credential that
            // expires mid-session — see SocketTransport.
            mintToken = { mintRealtimeToken(conversationId) },
            scope = scope,
            factory = socketFactory,
            reconnectDelayMs = reconnectDelayMs,
        )
        val job = scope.launch {
            socket.events.collect { _events.emit(it) }
        }
        synchronized(lock) {
            realtime = socket
            pump = job
            boundConversationId = conversationId
        }
        socket.connect()
    }

    /**
     * `POST /v1/widget/realtime/token` — the credential the gateway accepts.
     *
     * NOT the session token. There are two visitor credentials and they are
     * not interchangeable: the session token identifies the visitor to the
     * REST surface and is refused by the gateway; this one is HS256,
     * conversation-scoped, and lasts an hour.
     */
    internal suspend fun mintRealtimeToken(conversationId: String): String {
        val body = JSONObject().apply {
            put("publicId", channelPublicId)
            put("visitorToken", ensureToken())
            put("conversationId", conversationId)
        }
        return JSONObject(post("/v1/widget/realtime/token", body)).getString("token")
    }

    /**
     * Where to open the socket. Same precedence as
     * `apps/widget/src/realtime-url.ts`: an explicit override, then whatever
     * `init` reported, then the `api.` → `realtime.` convention.
     */
    internal fun resolveRealtimeUrl(): String {
        realtimeUrlOverride?.takeIf { it.isNotEmpty() }?.let { return it }
        serverRealtimeUrl?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            val uri = URI(baseUrl)
            val host = uri.host ?: return@runCatching baseUrl
            val swapped = if (host.startsWith("api.")) "realtime.${host.removePrefix("api.")}" else host
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "${uri.scheme}://$swapped$port"
        }.getOrDefault(baseUrl)
    }

    // ───────────────────────── UI ─────────────────────────

    /** Present the bundled chat surface. */
    public fun present(context: Context) {
        context.startActivity(Intent(context, ChatlyActivity::class.java))
    }

    // ───────────────────────── plumbing ─────────────────────────

    /**
     * The visitor token every widget route requires in its BODY.
     *
     * Inits on demand rather than making the host app sequence it: `track()`
     * on first launch is a legitimate first call, and the schema's
     * `visitorToken` is `min(1)` — an empty string is a 400, not an anonymous
     * session.
     */
    private suspend fun ensureToken(): String {
        storage.read()?.takeIf { it.isNotEmpty() }?.let { return it }
        return initSession().session.token
    }

    /**
     * Every write carries an `Idempotency-Key`, per the API convention. The
     * credential goes in the body and NOT in an `Authorization` header: the
     * widget surface is `@Public()` and has never read one, so a bearer token
     * here was decoration on a request that failed for a different reason.
     */
    internal suspend fun post(path: String, body: JSONObject): String {
        val res = transport.postJson(
            url = "$baseUrl$path",
            json = body.toString(),
            headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        if (!res.ok) {
            throw RuntimeException("chatly ${res.status}: ${res.body}")
        }
        return res.body
    }
}

// `internal`, not `private`: `Chatly+Kb.kt` builds its bodies with this too.
internal fun JSONObject.putIfNotNull(key: String, value: Any?) {
    if (value != null) put(key, value)
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val out = mutableMapOf<String, Any?>()
    for (key in keys()) out[key] = get(key)
    return out
}

/**
 * The attachment shape a message carries — `widgetAttachmentSchema`. Every
 * field is required there, which is why none of these is conditional.
 */
private fun List<Attachment>.toJsonArray(): JSONArray {
    val array = JSONArray()
    for (a in this) {
        array.put(
            JSONObject().apply {
                put("id", a.id)
                put("filename", a.filename)
                put("mimeType", a.mimeType)
                put("sizeBytes", a.sizeBytes)
                put("url", a.url)
            },
        )
    }
    return array
}

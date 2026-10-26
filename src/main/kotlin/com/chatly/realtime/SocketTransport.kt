package com.chatly.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The visitor's live connection, as decoded [RealtimeEnvelope]s.
 *
 * ── What this file used to get wrong ───────────────────────────────────────
 *
 * Three things, each enough on its own to make realtime inert:
 *
 *  1. It listened on `"envelope"`. The gateway emits on `"event"`
 *     (`apps/realtime/src/gateway.ts:394`), so nothing ever arrived.
 *  2. It passed `auth = mapOf("token" to token)` with no `kind`. `kind`
 *     defaults to `agent` at the gateway, which verifies RS256; a visitor's
 *     HS256 token fails that and the handshake is refused.
 *  3. Its own KDoc said to pass "the auth/session token from
 *     `/v1/widget/init`" — the wrong credential entirely. The session token
 *     is never accepted by the gateway. The realtime token is a separate,
 *     conversation-scoped HS256 JWT from `POST /v1/widget/realtime/token`
 *     with a 1h TTL.
 *
 * It also exposed `subscribe(channel)`. A visitor socket does not subscribe:
 * `handleVisitorConnection` joins `v:<workspace>:<conversation>` from the
 * token's own claims and the gateway registers the `subscribe` listener on
 * AGENT connections only. The method is gone rather than kept as a no-op.
 *
 * ── Why reconnection is ours and not socket.io's ───────────────────────────
 *
 * The Java client's `Manager.Options.auth` is a `Map<String, String>` field.
 * There is no per-attempt callback — the browser client has one and the widget
 * uses it precisely so a reconnect re-reads the credential. With socket.io's
 * backoff enabled, the map from the first attempt is replayed forever, so a
 * socket that drops after an hour comes back with an expired token and is
 * refused for the rest of the session. So: `reconnection = false` at the
 * factory, and [mintToken] re-invoked before **every** attempt, including the
 * first. A frozen credential would be the same bug with a different shape.
 */
public class SocketTransport(
    private val url: String,
    /**
     * Mints a FRESH conversation-scoped realtime token. Called once per
     * connect attempt — not once per [SocketTransport].
     */
    private val mintToken: suspend () -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val factory: RealtimeSocketFactory = IoRealtimeSocketFactory(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val reconnectDelayMs: Long = 1_000L,
    private val reconnectDelayMaxMs: Long = 10_000L,
) {
    private val _events = MutableSharedFlow<RealtimeEnvelope>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Decoded inbound envelopes. Hot — start collecting before [connect]. */
    public val events: SharedFlow<RealtimeEnvelope> = _events.asSharedFlow()

    private val lock = Any()
    private var socket: RealtimeSocket? = null
    private var reconnect: Job? = null
    private var attempt: Int = 0
    private var stoodDown: Boolean = true

    /**
     * The handshake payload, built fresh.
     *
     * `internal` so a test can assert on it without opening a socket, though
     * the tests that matter assert on what the FACTORY was handed — that is
     * the value the gateway actually sees.
     */
    internal suspend fun authPayload(): Map<String, String> =
        mapOf(AUTH_TOKEN_KEY to mintToken(), AUTH_KIND_KEY to VISITOR_KIND)

    /**
     * Open the connection. Safe to call again: the previous socket is closed
     * first, synchronously, so a conversation switch cannot leave a socket
     * nobody owns listening to the old thread's events.
     */
    public suspend fun connect() {
        disconnect()
        synchronized(lock) { stoodDown = false }
        open()
    }

    private suspend fun open() {
        val auth = authPayload()
        synchronized(lock) {
            // Minting is a round trip. `disconnect()` may have run while it
            // was in flight, in which case connecting would open a socket for
            // a conversation nobody is looking at.
            if (stoodDown) return
        }
        val s = factory.open(url, auth)
        s.onConnect {
            synchronized(lock) { attempt = 0 }
        }
        s.onEvent { raw -> emitDecoded(raw) }
        s.onDisconnect { scheduleReconnect() }
        synchronized(lock) {
            if (stoodDown) {
                s.close()
                return
            }
            socket = s
        }
        s.connect()
    }

    /**
     * Re-mint, then reconnect, with exponential backoff capped at
     * [reconnectDelayMaxMs].
     */
    private fun scheduleReconnect() {
        val delayMs = synchronized(lock) {
            if (stoodDown || reconnect?.isActive == true) return
            attempt += 1
            val shift = (attempt - 1).coerceAtMost(4)
            (reconnectDelayMs shl shift).coerceAtMost(reconnectDelayMaxMs)
        }
        val job = scope.launch {
            delay(delayMs)
            runCatching { open() }
        }
        synchronized(lock) { reconnect = job }
    }

    /**
     * Report a read receipt — the one event a visitor may publish.
     *
     * The payload is `messageReadPayloadSchema`, in full. It used to be
     * `{"conversationId","messageId"}`, which the gateway's `safeParse`
     * rejected on three missing required fields AND a misnamed anchor, after
     * which the handler returned silently. Even a successful parse would then
     * have been refused: the visitor branch requires `readerType == "contact"`
     * and `readerId == contactId` from the socket's own token, so a receipt
     * that does not name its reader can never be accepted.
     *
     * `readAt` is ISO-8601 with a `Z` offset because the schema is
     * `z.string().datetime()`, which rejects an offset like `+01:00`.
     */
    public fun emitRead(conversationId: String, contactId: String, upToMessageId: String? = null) {
        /*
         * Built with `buildJsonObject` rather than string concatenation.
         * The first draft of this fix assembled the optional anchor inside a
         * raw string and wrote `\"` to close it — in a Kotlin raw string a
         * backslash is literal, so that would have emitted a stray backslash
         * and produced invalid JSON: the same silent `safeParse` failure this
         * method is being fixed for, reintroduced by the fix.
         */
        val payload =
            buildJsonObject {
                put("conversationId", JsonPrimitive(conversationId))
                put("readAt", JsonPrimitive(isoNow()))
                put("readerType", JsonPrimitive("contact"))
                put("readerId", JsonPrimitive(contactId))
                if (upToMessageId != null) {
                    put("upToMessageId", JsonPrimitive(upToMessageId))
                }
            }.toString()
        synchronized(lock) { socket }?.emit(MESSAGE_READ_CHANNEL, payload)
    }

    /**
     * `readAt` for a read receipt: UTC, milliseconds, `Z`.
     *
     * `z.string().datetime()` with no options accepts only a `Z` offset, so a
     * device-local offset would be refused by the same silent `safeParse`
     * this method's payload was already failing.
     */
    private fun isoNow(): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now())

    /** Close, and stop reconnecting. */
    public fun disconnect() {
        val (old, job) = synchronized(lock) {
            stoodDown = true
            attempt = 0
            val pair = socket to reconnect
            socket = null
            reconnect = null
            pair
        }
        job?.cancel()
        old?.close()
    }

    private fun emitDecoded(raw: String) {
        runCatching { json.decodeFromString(RealtimeEnvelope.serializer(), raw) }
            .onSuccess { _events.tryEmit(it) }
    }
}

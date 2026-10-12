package com.chatly.realtime

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.net.URI

/**
 * Thin wrapper around `io.socket.client.IO` that emits decoded
 * [RealtimeEnvelope]s as a [SharedFlow]. Construct once per workspace
 * connection; pass the auth/session token from `/v1/widget/init` in [token].
 *
 * Uses Socket.IO's auth handshake (`auth.token`) so the realtime service can
 * authenticate without a separate REST round-trip.
 */
public class SocketTransport(
    private val url: String,
    private val token: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val _events = MutableSharedFlow<RealtimeEnvelope>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Decoded inbound envelopes. Hot — start collecting before [connect]. */
    public val events: SharedFlow<RealtimeEnvelope> = _events.asSharedFlow()

    private var socket: Socket? = null

    /** Open the underlying Socket.IO connection. Idempotent. */
    public fun connect() {
        if (socket?.connected() == true) return
        val opts = IO.Options().apply {
            transports = arrayOf("websocket")
            reconnection = true
            reconnectionDelay = 1_000
            auth = mapOf("token" to token)
        }
        val s = IO.socket(URI.create(url), opts)
        s.on("envelope") { args ->
            val first = args.firstOrNull() ?: return@on
            val text = when (first) {
                is JSONObject -> first.toString()
                is String -> first
                else -> first.toString()
            }
            runCatching { json.decodeFromString(RealtimeEnvelope.serializer(), text) }
                .onSuccess { _events.tryEmit(it) }
        }
        s.connect()
        socket = s
    }

    /**
     * Subscribe to one or more channels, optionally with a replay cursor.
     * Caller is responsible for [connect]ing first.
     */
    public fun subscribe(channel: String, lastEventId: String? = null) {
        val body = JSONObject().apply {
            put("channels", listOf(channel))
            if (lastEventId != null) put("lastEventId", lastEventId)
        }
        socket?.emit("subscribe", body)
    }

    /** Close the socket and release resources. */
    public fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}

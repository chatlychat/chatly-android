package com.chatly.realtime

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI

/** The Socket.IO channel the gateway EMITS visitor envelopes on. */
public const val INBOUND_EVENT_CHANNEL: String = "event"

/** The one event a visitor is allowed to publish (`apps/realtime/src/gateway.ts:399`). */
public const val MESSAGE_READ_CHANNEL: String = "message.read"

/** Handshake auth keys. The gateway parses `{ token, kind }` and nothing else. */
public const val AUTH_TOKEN_KEY: String = "token"
public const val AUTH_KIND_KEY: String = "kind"

/**
 * `kind` is OPTIONAL at the gateway and **defaults to `agent`**.
 *
 * A handshake without it is routed to `authenticateHandshakeToken`, which
 * verifies RS256 against the platform's public key; a visitor's HS256 token
 * fails that verify and the gateway answers a bare `Unauthorized`. Omitting
 * this one string is the single defect behind "mobile realtime does not work"
 * in all four mobile SDKs.
 */
public const val VISITOR_KIND: String = "visitor"

/**
 * The socket, behind an interface.
 *
 * Exists so a test can see the **handshake it was opened with** — the auth map
 * is the half a fake transport cannot fake, and the half every bug in this
 * file has lived in.
 */
public interface RealtimeSocket {
    /** Connected. Used to reset the reconnect backoff. */
    public fun onConnect(handler: () -> Unit)

    /** One inbound envelope, as raw JSON text. */
    public fun onEvent(handler: (String) -> Unit)

    /** Dropped, with the transport's reason when it gave one. */
    public fun onDisconnect(handler: (String?) -> Unit)

    /** Publish. [payloadJson] is a JSON object literal. */
    public fun emit(event: String, payloadJson: String)

    public fun connect()

    /** Close and release every listener. */
    public fun close()
}

/**
 * Opens one socket per connect ATTEMPT, with the auth map it is handed.
 *
 * A factory and not a constructor argument because the credential cannot be
 * captured once: see [SocketTransport] for why this client reconnects by hand.
 */
public fun interface RealtimeSocketFactory {
    public fun open(url: String, auth: Map<String, String>): RealtimeSocket
}

/**
 * The real factory, over `io.socket:socket.io-client`.
 *
 * `reconnection = false` is deliberate and is the whole reason this file is
 * shaped like this. `Manager.Options.auth` is a plain
 * `Map<String, String>` — the Java client has **no per-attempt auth hook**,
 * unlike the browser client the widget uses
 * (`apps/widget/src/realtime-client.ts:89`, which passes a callback). With
 * socket.io's own backoff enabled, the map captured on the first attempt is
 * replayed verbatim forever, so a visitor whose socket dropped after the
 * token's 1h TTL reconnects with an expired credential and is refused
 * silently for the rest of the session. [SocketTransport] therefore drives
 * reconnection itself and mints a fresh token before each attempt.
 *
 * `forceNew = true` for the same reason: `IO.socket` caches a Manager per
 * (url, path) and would otherwise hand back the previous one — with the
 * previous auth map on it.
 */
public class IoRealtimeSocketFactory(
    private val socketIoPath: String = "/socket.io",
    private val timeoutMs: Long = 10_000L,
) : RealtimeSocketFactory {
    override fun open(url: String, auth: Map<String, String>): RealtimeSocket {
        val opts = IO.Options().apply {
            path = socketIoPath
            transports = arrayOf("websocket")
            reconnection = false
            forceNew = true
            timeout = timeoutMs
            this.auth = auth
        }
        return IoRealtimeSocket(IO.socket(URI.create(url), opts))
    }
}

/** [RealtimeSocket] over a concrete `io.socket.client.Socket`. */
internal class IoRealtimeSocket(private val socket: Socket) : RealtimeSocket {

    override fun onConnect(handler: () -> Unit) {
        socket.on(Socket.EVENT_CONNECT) { handler() }
    }

    override fun onEvent(handler: (String) -> Unit) {
        socket.on(INBOUND_EVENT_CHANNEL) { args ->
            val first = args.firstOrNull() ?: return@on
            handler(if (first is String) first else first.toString())
        }
    }

    override fun onDisconnect(handler: (String?) -> Unit) {
        socket.on(Socket.EVENT_DISCONNECT) { args ->
            handler(args.firstOrNull()?.toString())
        }
        // A refused handshake arrives here, not on EVENT_DISCONNECT, and it is
        // the failure mode an expired token produces — so it has to reach the
        // reconnect path too or a token that aged out ends the session.
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            handler(args.firstOrNull()?.toString())
        }
    }

    override fun emit(event: String, payloadJson: String) {
        socket.emit(event, JSONObject(payloadJson))
    }

    override fun connect() {
        socket.connect()
    }

    override fun close() {
        socket.off()
        socket.disconnect()
    }
}

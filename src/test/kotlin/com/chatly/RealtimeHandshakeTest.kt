package com.chatly

import com.chatly.realtime.AUTH_KIND_KEY
import com.chatly.realtime.AUTH_TOKEN_KEY
import com.chatly.realtime.INBOUND_EVENT_CHANNEL
import com.chatly.realtime.IoRealtimeSocket
import com.chatly.realtime.SocketTransport
import com.chatly.realtime.VISITOR_KIND
import com.chatly.storage.InMemoryVisitorTokenStorage
import com.chatly.testing.RecordingSocketFactory
import com.chatly.testing.RecordingTransport
import com.chatly.testing.awaitTrue
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The handshake, asserted on the auth map the SDK opened the socket with.
 *
 * Three separate defects lived here and a response-shaped test could see none
 * of them: no `kind` (so the gateway routed a visitor's HS256 token to the
 * RS256 agent verifier and answered a bare `Unauthorized`), the session token
 * instead of a minted realtime token, and a credential captured once and
 * replayed on every reconnect past its 1h TTL.
 */
class RealtimeHandshakeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `the handshake carries kind visitor`() = runBlocking {
        val sockets = RecordingSocketFactory()
        val transport = SocketTransport(
            url = "https://realtime.test",
            mintToken = { "rt_1" },
            scope = scope,
            factory = sockets,
            reconnectDelayMs = 1L,
        )
        transport.connect()

        val auth = sockets.opened.single().auth
        assertEquals(
            VISITOR_KIND,
            auth[AUTH_KIND_KEY],
            "`kind` is optional at the gateway and DEFAULTS TO AGENT; omitting it routes the " +
                "handshake to the RS256 verifier, which refuses every visitor token",
        )
        assertEquals("rt_1", auth[AUTH_TOKEN_KEY])
        assertEquals(
            setOf(AUTH_TOKEN_KEY, AUTH_KIND_KEY),
            auth.keys,
            "handshakeAuthSchema parses exactly { token, kind }",
        )
        transport.disconnect()
    }

    @Test
    fun `a second connect attempt re-reads the auth payload`() = runBlocking {
        val sockets = RecordingSocketFactory()
        val minted = AtomicInteger(0)
        val transport = SocketTransport(
            url = "https://realtime.test",
            mintToken = { "rt_${minted.incrementAndGet()}" },
            scope = scope,
            factory = sockets,
            reconnectDelayMs = 1L,
        )
        transport.connect()
        assertEquals("rt_1", sockets.opened[0].auth[AUTH_TOKEN_KEY])

        // The socket drops after the token's lifetime. socket.io's own backoff
        // would replay the captured map here; this client re-mints instead.
        sockets.opened[0].drop()
        awaitTrue(what = "a second socket to be opened") { sockets.opened.size >= 2 }

        assertEquals(
            "rt_2",
            sockets.opened[1].auth[AUTH_TOKEN_KEY],
            "a reconnect must mint a FRESH token — a replayed one is expired and refused silently",
        )
        assertEquals(VISITOR_KIND, sockets.opened[1].auth[AUTH_KIND_KEY])
        assertNotEquals(sockets.opened[0].auth, sockets.opened[1].auth)
        transport.disconnect()
    }

    @Test
    fun `disconnect stops the client reconnecting`() = runBlocking {
        val sockets = RecordingSocketFactory()
        val transport = SocketTransport(
            url = "https://realtime.test",
            mintToken = { "rt" },
            scope = scope,
            factory = sockets,
            reconnectDelayMs = 1L,
        )
        transport.connect()
        transport.disconnect()
        assertTrue(sockets.opened.single().closed)

        sockets.opened[0].drop()
        Thread.sleep(60)
        assertEquals(1, sockets.opened.size, "a deliberate disconnect must not be reconnected")
    }

    /**
     * The channel NAME, pinned against a real `io.socket.client.Socket`.
     *
     * The test below it — the one that delivers an envelope through
     * [com.chatly.testing.FakeSocket] — cannot see this: the fake's `onEvent`
     * takes a handler and no channel, so it agrees with any name at all. The
     * constant was `"envelope"`, which matches nothing the gateway does, and
     * a fake-only suite stayed green through it. So this asserts on the
     * listener map of the real adapter.
     *
     * `IO.socket` builds a Manager but opens nothing until `connect()`, so no
     * network is touched here.
     */
    @Test
    fun `the real adapter registers the channels the gateway speaks`() {
        val socket = IO.socket(URI.create("https://realtime.invalid"), IO.Options())
        val adapter = IoRealtimeSocket(socket)
        adapter.onEvent { }
        adapter.onConnect { }
        adapter.onDisconnect { }

        assertTrue(
            socket.hasListeners(INBOUND_EVENT_CHANNEL),
            "the gateway emits on `event` (gateway.ts:394); this was `envelope` and nothing arrived",
        )
        assertFalse(socket.hasListeners("envelope"), "`envelope` is not a channel the server uses")
        assertTrue(socket.hasListeners(Socket.EVENT_CONNECT))
        assertTrue(socket.hasListeners(Socket.EVENT_DISCONNECT))
        assertTrue(
            socket.hasListeners(Socket.EVENT_CONNECT_ERROR),
            "a refused handshake arrives as a connect error, and it is what an expired token produces",
        )
    }

    @Test
    fun `an inbound envelope is decoded and dispatched`() = runBlocking {
        val sockets = RecordingSocketFactory()
        val transport = SocketTransport(
            url = "https://realtime.test",
            mintToken = { "rt" },
            scope = scope,
            factory = sockets,
            reconnectDelayMs = 1L,
        )
        val seen = mutableListOf<String>()
        val collector = scope.launch { transport.events.collect { seen.add(it.type) } }
        transport.connect()
        // Give the collector a moment to subscribe to the hot flow.
        Thread.sleep(50)

        sockets.opened.single().deliver(
            """{"v":1,"type":"message.created","id":"e1","ts":1,
                "workspace_id":"0190b8c3-4d2b-7c00-8000-000000000002",
                "payload":{"body":"hi"}}""",
        )
        awaitTrue(what = "the envelope to be dispatched") { seen.isNotEmpty() }
        assertEquals("message.created", seen.single())
        collector.cancel()
        transport.disconnect()
    }

    // ───────────── the credential, end to end through the client ─────────────

    private val initBody = """
        {"session":{"token":"sess_visitor_1","contactId":"c1","workspaceId":"w1",
                    "brandId":"b1","channelId":"ch1"},
         "workspace":{"id":"w1","name":"Acme"},
         "realtimeUrl":"https://realtime.test"}
    """.trimIndent()

    private fun responses(): Map<String, (Int) -> String> = mapOf(
        "/v1/widget/init" to { _ -> initBody },
        "/v1/widget/realtime/token" to { n -> """{"token":"rt_$n","expiresAt":"2026-10-12T23:00:00.000Z"}""" },
        "/v1/widget/conversations/list" to { _ ->
            """{"conversations":[{"id":"conv_1","status":"open","createdAt":"2026-10-12T00:00:00.000Z"}]}"""
        },
    )

    @Test
    fun `the socket token is minted by the realtime route, not the session token`() = runBlocking {
        val http = RecordingTransport(responses())
        val sockets = RecordingSocketFactory()
        val chatly = Chatly(
            channelPublicId = "CH_test",
            baseUrl = "https://api.test",
            transport = http,
            storage = InMemoryVisitorTokenStorage(),
            socketFactory = sockets,
            realtimeUrlOverride = null,
            reconnectDelayMs = 1L,
        )
        chatly.openConversation("conv_1")

        val mint = http.lastFor("/v1/widget/realtime/token")
        assertEquals("CH_test", mint.str("publicId"))
        assertEquals("sess_visitor_1", mint.str("visitorToken"))
        assertEquals(
            "conv_1",
            mint.str("conversationId"),
            "the realtime token is conversation-SCOPED; widgetRealtimeTokenSchema requires the id",
        )

        val auth = sockets.opened.single().auth
        assertEquals("rt_1", auth[AUTH_TOKEN_KEY])
        assertNotEquals(
            "sess_visitor_1",
            auth[AUTH_TOKEN_KEY],
            "the visitor SESSION token is not accepted by the realtime gateway; " +
                "SocketTransport's own KDoc used to tell integrators to pass it",
        )
        assertEquals(VISITOR_KIND, auth[AUTH_KIND_KEY])
        chatly.disconnectRealtime()
    }

    @Test
    fun `switching conversations re-mints and reconnects`() = runBlocking {
        val http = RecordingTransport(responses())
        val sockets = RecordingSocketFactory()
        val chatly = Chatly(
            channelPublicId = "CH_test",
            baseUrl = "https://api.test",
            transport = http,
            storage = InMemoryVisitorTokenStorage(),
            socketFactory = sockets,
            realtimeUrlOverride = null,
            reconnectDelayMs = 1L,
        )
        chatly.openConversation("conv_1")
        chatly.openConversation("conv_1")
        assertEquals(1, sockets.opened.size, "re-opening the same thread must not churn the socket")

        chatly.openConversation("conv_2")
        assertEquals(2, sockets.opened.size)
        assertTrue(sockets.opened[0].closed, "the old socket would keep delivering the old thread")
        assertEquals("conv_2", http.lastFor("/v1/widget/realtime/token").str("conversationId"))
        chatly.disconnectRealtime()
    }

    @Test
    fun `the realtime url falls back to the api host convention`() {
        val chatly = Chatly(
            channelPublicId = "CH_test",
            baseUrl = "https://api.chatlychat.com",
            transport = RecordingTransport(emptyMap()),
            storage = InMemoryVisitorTokenStorage(),
            socketFactory = RecordingSocketFactory(),
            realtimeUrlOverride = null,
        )
        assertEquals("https://realtime.chatlychat.com", chatly.resolveRealtimeUrl())
    }

    @Test
    fun `an explicit realtime url wins`() {
        val chatly = Chatly(
            channelPublicId = "CH_test",
            baseUrl = "https://api.chatlychat.com",
            transport = RecordingTransport(emptyMap()),
            storage = InMemoryVisitorTokenStorage(),
            socketFactory = RecordingSocketFactory(),
            realtimeUrlOverride = "https://rt.example.test",
        )
        assertEquals("https://rt.example.test", chatly.resolveRealtimeUrl())
    }
}

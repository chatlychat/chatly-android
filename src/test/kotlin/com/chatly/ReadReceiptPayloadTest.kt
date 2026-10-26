package com.chatly

import com.chatly.realtime.SocketTransport
import com.chatly.testing.RecordingSocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The read receipt, asserted on the payload the SDK PUBLISHES.
 *
 * `emitRead` sent `{"conversationId","messageId"}` and was a silent no-op on
 * every installed Android app. `messageReadPayloadSchema` names the anchor
 * `upToMessageId` and additionally requires `readAt`, `readerType` and
 * `readerId`, so the gateway's `safeParse` failed and the handler returned
 * without a word: the agent never saw the "Read" tick and the visitor's badge
 * never cleared, with no error anywhere. Even a successful parse would then
 * have been refused — the visitor branch requires `readerType == "contact"`
 * and `readerId == contactId` from the socket's own token.
 *
 * `FakeSocket.published` has recorded every emit all along and no test had
 * ever read it. That is the gap this file closes, and it is the same gap the
 * handshake tests were written to close one layer up: assert on what the SDK
 * SENT, because a fake that echoes a canned success agrees with the bug.
 */
class ReadReceiptPayloadTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun emitted(upTo: String?): Map<String, String> = runBlocking {
        val sockets = RecordingSocketFactory()
        val transport = SocketTransport(
            url = "https://realtime.test",
            mintToken = { "rt_1" },
            scope = scope,
            factory = sockets,
            reconnectDelayMs = 1L,
        )
        transport.connect()
        transport.emitRead("conv-1", "contact-9", upTo)
        val (event, payload) = sockets.opened.single().published.single()
        assertEquals("message.read", event)
        transport.disconnect()
        Json.parseToJsonElement(payload).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun `the receipt carries every field the gateway requires`() {
        val body = emitted("msg-7")
        assertEquals(
            setOf("conversationId", "readAt", "readerType", "readerId", "upToMessageId"),
            body.keys,
            "messageReadPayloadSchema requires conversationId, readAt, readerType and readerId; " +
                "the anchor is upToMessageId and was sent as `messageId`",
        )
        assertEquals("conv-1", body["conversationId"])
        assertEquals("msg-7", body["upToMessageId"])
        assertEquals("contact", body["readerType"])
        assertEquals("contact-9", body["readerId"])
    }

    @Test
    fun `readAt is a Z-offset instant, which is all z-string-datetime accepts`() {
        val readAt = emitted(null)["readAt"]
        assertTrue(
            readAt != null && readAt.endsWith("Z"),
            "`z.string().datetime()` with no options rejects an offset like +01:00, so a " +
                "device-local timestamp would fail the same silent safeParse: got $readAt",
        )
    }

    @Test
    fun `the anchor is omitted rather than sent as null when absent`() {
        val body = emitted(null)
        assertEquals(
            setOf("conversationId", "readAt", "readerType", "readerId"),
            body.keys,
            "upToMessageId is optional server-side; a literal null is not the same as absent",
        )
    }
}

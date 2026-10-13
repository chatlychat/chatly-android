package com.chatly

import com.chatly.models.Attachment
import com.chatly.storage.InMemoryVisitorTokenStorage
import com.chatly.testing.RecordingSocketFactory
import com.chatly.testing.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What the SDK SENDS, asserted key by key against the zod schemas in
 * `apps/api/src/modules/widget/widget.schemas.ts`.
 *
 * This is the half that was broken and the half the previous suite could not
 * see. `EnvelopeTest` encoded an envelope and decoded it again, which proved
 * the serializer worked while every single HTTP call in the package was a 400:
 * `channelPublicId` where the server reads `publicId`, no `visitorToken` at
 * all on three routes, and the credential in an `Authorization` header the
 * widget surface has never read. zod STRIPS an unknown key, so none of that
 * produced a loud failure — the field was simply missing, and the server did
 * something else.
 */
class RequestShapeTest {

    private val initBody = """
        {"session":{"token":"sess_visitor_1","contactId":"c1","workspaceId":"w1",
                    "brandId":"b1","channelId":"ch1"},
         "workspace":{"id":"w1","name":"Acme"},
         "sessionToken":"TOP_LEVEL_DECOY",
         "realtimeUrl":"https://realtime.test"}
    """.trimIndent()

    private fun responses(): Map<String, (Int) -> String> = mapOf(
        "/v1/widget/init" to { _ -> initBody },
        "/v1/widget/realtime/token" to { n -> """{"token":"rt_$n","expiresAt":"2026-10-12T23:00:00.000Z"}""" },
        "/v1/widget/events" to { _ -> """{"tracked":true}""" },
        "/v1/widget/push/register" to { _ -> """{"id":"p1","created":true}""" },
        "/v1/widget/conversations" to { _ ->
            """{"conversation":{"id":"conv_1"},
                "message":{"id":"m1","body":"hi","createdAt":"2026-10-12T00:00:00.000Z"}}"""
        },
        "/v1/widget/messages" to { _ ->
            """{"id":"m2","authorType":"contact","kind":"text","body":"again",
                "createdAt":"2026-10-12T00:00:01.000Z"}"""
        },
        "/v1/widget/conversations/list" to { _ ->
            """{"conversations":[{"id":"conv_1","status":"open","createdAt":"2026-10-12T00:00:00.000Z"}]}"""
        },
        "/v1/widget/messages/list" to { _ ->
            """{"messages":[{"id":"m1","authorType":"agent","kind":"text","body":"hello",
                            "createdAt":"2026-10-12T00:00:00.000Z"}]}"""
        },
        "/v1/widget/uploads/presign" to { _ ->
            """{"uploadId":"up_1","url":"https://s3.test/put","method":"PUT",
                "headers":{"content-type":"image/png"},
                "publicUrl":"https://cdn.test/up_1.png",
                "expiresAt":"2026-10-12T00:05:00.000Z"}"""
        },
        "/v1/widget/kb/search" to { _ ->
            """{"results":[{"id":"a1","slug":"refunds","title":"Refunds","excerpt":"..."}]}"""
        },
    )

    private fun client(transport: RecordingTransport) = Chatly(
        channelPublicId = "CH_test",
        baseUrl = "https://api.test",
        transport = transport,
        storage = InMemoryVisitorTokenStorage(),
        socketFactory = RecordingSocketFactory(),
        realtimeUrlOverride = null,
        reconnectDelayMs = 1L,
    )

    @Test
    fun `init sends publicId and never channelPublicId`() = runBlocking {
        val transport = RecordingTransport(responses())
        client(transport).initSession()

        val init = transport.lastFor("/v1/widget/init")
        assertEquals("CH_test", init.str("publicId"), "widgetInitSchema requires publicId")
        assertFalse(
            init.has("channelPublicId"),
            "`channelPublicId` is not a key in widgetInitSchema — zod strips it and publicId is then missing",
        )
    }

    @Test
    fun `init reads the token from session dot token, not the top level`() = runBlocking {
        val transport = RecordingTransport(responses())
        val chatly = client(transport)
        chatly.initSession()

        // The canned response carries a top-level `sessionToken` that the real
        // API does not send. If the client reads it, this is what it carries
        // on the next call.
        chatly.track("checkout.completed")
        val tracked = transport.lastFor("/v1/widget/events")
        assertEquals(
            "sess_visitor_1",
            tracked.str("visitorToken"),
            "init answers session.token; the client used to read a top-level sessionToken",
        )
    }

    @Test
    fun `identify nests the identity rather than flattening it`() = runBlocking {
        val transport = RecordingTransport(responses())
        client(transport).identify(
            externalId = "u-42",
            email = "jamie@acme.io",
            name = "Jamie",
            userHash = "a".repeat(64),
            attributes = mapOf("plan" to "pro"),
        )

        val init = transport.lastFor("/v1/widget/init")
        assertFalse(init.has("externalId"), "externalId is not a top-level key in widgetInitSchema")
        val identity = init.json.getJSONObject("identity")
        assertEquals("u-42", identity.getString("externalId"))
        assertEquals("jamie@acme.io", identity.getString("email"))
        assertEquals("Jamie", identity.getString("name"))
        assertEquals("a".repeat(64), identity.getString("userHash"))
        assertEquals("pro", identity.getJSONObject("attributes").getString("plan"))
    }

    @Test
    fun `every write carries publicId and visitorToken in the body`() = runBlocking {
        val transport = RecordingTransport(responses())
        val chatly = client(transport)
        chatly.track("viewed", mapOf("sku" to "A1"))
        chatly.registerFcmToken("fcm-token-which-is-long-enough")
        chatly.startConversation("hello")
        chatly.replyToConversation("conv_1", "again")
        chatly.listConversations()
        chatly.listMessages("conv_1")

        val mustCarryBoth = listOf(
            "/v1/widget/events",
            "/v1/widget/push/register",
            "/v1/widget/conversations",
            "/v1/widget/messages",
            "/v1/widget/conversations/list",
            "/v1/widget/messages/list",
        )
        for (path in mustCarryBoth) {
            val request = transport.lastFor(path)
            assertEquals("CH_test", request.str("publicId"), "$path must carry publicId")
            assertEquals(
                "sess_visitor_1",
                request.str("visitorToken"),
                "$path requires visitorToken in the BODY — the surface reads no Authorization header",
            )
        }
    }

    @Test
    fun `the credential is in the body and not in a bearer header`() = runBlocking {
        val transport = RecordingTransport(responses())
        client(transport).track("viewed")

        for (request in transport.sent) {
            assertNull(
                request.headers["Authorization"],
                "the widget surface is @Public() and has never read an Authorization header",
            )
            assertTrue(
                request.headers.containsKey("Idempotency-Key"),
                "${request.path} is a write and the API convention requires an Idempotency-Key",
            )
        }
    }

    @Test
    fun `reply names the conversation`() = runBlocking {
        val transport = RecordingTransport(responses())
        client(transport).replyToConversation("conv_1", "again")

        val reply = transport.lastFor("/v1/widget/messages")
        assertEquals("conv_1", reply.str("conversationId"), "widgetReplySchema requires conversationId")
    }

    @Test
    fun `send opens a conversation when there is none, then replies on it`() = runBlocking {
        val transport = RecordingTransport(responses())
        val chatly = client(transport)
        chatly.send("first")
        chatly.send("second")

        assertEquals(1, transport.forPath("/v1/widget/conversations").size, "one thread, not two")
        val reply = transport.lastFor("/v1/widget/messages")
        assertEquals("conv_1", reply.str("conversationId"))
        assertEquals("conv_1", chatly.currentConversationId())
    }

    @Test
    fun `presign sends contentType and sizeBytes, never mimeType`() = runBlocking {
        val transport = RecordingTransport(responses())
        val bytes = ByteArray(1234) { 7 }
        val attachment = client(transport).uploadAttachment(bytes, "shot.png", "image/png")

        val presign = transport.lastFor("/v1/widget/uploads/presign")
        assertEquals("image/png", presign.str("contentType"), "widgetPresignSchema requires contentType")
        assertFalse(
            presign.has("mimeType"),
            "`mimeType` is the ATTACHMENT's key, not the presign route's — zod strips it and contentType is then missing",
        )
        assertEquals("1234", presign.str("sizeBytes"), "widgetPresignSchema requires a positive sizeBytes")
        assertEquals("shot.png", presign.str("filename"))

        // Step 2 is the raw bytes to the presigned URL, with its headers.
        assertEquals(1, transport.uploads.size, "the bytes must actually be uploaded")
        assertEquals("https://s3.test/put", transport.uploads[0].first)
        assertEquals("PUT", transport.uploads[0].second)
        assertEquals(1234, transport.uploads[0].third)

        // Step 3 renames contentType back to mimeType on the attachment.
        assertEquals("up_1", attachment.id)
        assertEquals("https://cdn.test/up_1.png", attachment.url)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(1234L, attachment.sizeBytes)
    }

    @Test
    fun `an attachment travels with every field widgetAttachmentSchema requires`() = runBlocking {
        val transport = RecordingTransport(responses())
        val attachment = Attachment(
            id = "up_1",
            url = "https://cdn.test/up_1.png",
            filename = "shot.png",
            mimeType = "image/png",
            sizeBytes = 1234L,
        )
        client(transport).startConversation("look", listOf(attachment))

        val sent = transport.lastFor("/v1/widget/conversations").json
            .getJSONArray("attachments").getJSONObject(0)
        assertEquals("up_1", sent.getString("id"))
        assertEquals("shot.png", sent.getString("filename"))
        assertEquals("image/png", sent.getString("mimeType"))
        assertEquals(1234, sent.getInt("sizeBytes"))
        assertEquals("https://cdn.test/up_1.png", sent.getString("url"))
    }

    @Test
    fun `kb search is reachable and sends an optional visitorToken only once it has one`() = runBlocking {
        val transport = RecordingTransport(responses())
        val chatly = client(transport)
        val results = chatly.searchKb("refunds")
        assertEquals("refunds", results.single().slug)

        // `visitorToken` is `.optional()` on widgetKbSearchSchema, so an
        // anonymous visitor searching before init must not send an empty
        // string — `min(1)` would refuse it.
        val search = transport.lastFor("/v1/widget/kb/search")
        assertEquals("CH_test", search.str("publicId"))
        assertFalse(search.has("visitorToken"), "no token yet means the key is absent, not empty")
        assertEquals("refunds", search.str("query"))
    }
}

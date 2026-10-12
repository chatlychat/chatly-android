package com.chatly

import com.chatly.realtime.REALTIME_PROTOCOL_VERSION
import com.chatly.realtime.RealtimeEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class EnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `encodes message_created envelope with snake_cased workspace_id`() {
        val envelope = RealtimeEnvelope(
            type = "message.created",
            id = "0190b8c3-4d2b-7c00-8000-000000000001",
            ts = 1_716_900_000_000L,
            workspaceId = "0190b8c3-4d2b-7c00-8000-000000000002",
            payload = buildJsonObject {
                put("body", JsonPrimitive("hello"))
            },
        )
        val encoded = json.encodeToString(RealtimeEnvelope.serializer(), envelope)
        assertEquals(true, encoded.contains("\"workspace_id\""), "must use snake_cased workspace_id")
        assertEquals(true, encoded.contains("\"v\":$REALTIME_PROTOCOL_VERSION"))
    }

    @Test
    fun `round trips a typing_start envelope`() {
        val raw = """
            {"v":1,"type":"typing.start","id":"0190b8c3-4d2b-7c00-8000-000000000003",
             "ts":1716900000123,"workspace_id":"0190b8c3-4d2b-7c00-8000-000000000004",
             "payload":{"conversationId":"0190b8c3-4d2b-7c00-8000-000000000005",
                        "actorType":"contact","actorId":"v_42"}}
        """.trimIndent()
        val decoded = json.decodeFromString(RealtimeEnvelope.serializer(), raw)
        assertEquals("typing.start", decoded.type)
        assertEquals(1, decoded.v)
        assertEquals("0190b8c3-4d2b-7c00-8000-000000000004", decoded.workspaceId)
        val payload = decoded.payload as JsonObject
        assertEquals("contact", (payload["actorType"] as JsonPrimitive).content)

        val re = json.encodeToString(RealtimeEnvelope.serializer(), decoded)
        val again = json.decodeFromString(RealtimeEnvelope.serializer(), re)
        assertEquals(decoded, again)
    }
}

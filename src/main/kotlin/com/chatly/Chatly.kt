package com.chatly

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Chatly Android SDK (Kotlin).
 *
 * Tiny pure-stdlib client — drop into any Android (or JVM) app. For
 * production builds you'll want to swap the request layer for OkHttp.
 */
/*
 * The default base URL is PRODUCTION.
 *
 * It was `https://api.chatly.example` — a reserved RFC 2606 domain that
 * resolves nowhere, so every call failed for anyone who did not override it.
 * The same defect shipped in the JS and Node SDKs (`api.livechat.local`) and
 * was reported by a customer integrating from the docs alone. A self-hosted
 * install passes its own.
 */
class Chatly private constructor(
    val channelPublicId: String,
    val baseUrl: String = "https://api.chatlychat.com",
) {
    private var sessionToken: String? = null

    companion object {
        @Volatile private var instance: Chatly? = null
        fun configure(channelPublicId: String, baseUrl: String = "https://api.chatlychat.com"): Chatly {
            return instance ?: synchronized(this) {
                instance ?: Chatly(channelPublicId, baseUrl).also { instance = it }
            }
        }
        fun shared(): Chatly = instance ?: throw IllegalStateException("Chatly.configure() not called")
    }

    suspend fun identify(externalId: String?, email: String? = null, name: String? = null, attributes: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val body = mutableMapOf<String, Any?>(
            "channelPublicId" to channelPublicId,
            "externalId" to externalId,
            "email" to email,
            "name" to name,
            "attributes" to attributes,
        )
        // No `/v1/widget/identify` route has ever existed. Identity is accepted
        // by `init`, which is also where verified-identity hashing is checked.
        return post("/v1/widget/init", body)
    }

    suspend fun track(name: String, properties: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        post("/v1/widget/events", mapOf("channelPublicId" to channelPublicId, "name" to name, "properties" to properties))

    suspend fun send(message: String): Map<String, Any?> =
        post("/v1/widget/messages", mapOf("body" to message, "kind" to "text"))

    suspend fun registerFcmToken(token: String): Map<String, Any?> =
        // The agent-side `/v1/push-devices` surface is unrelated; visitor push
        // registers through the widget.
        post("/v1/widget/push/register", mapOf("platform" to "fcm", "token" to token))

    private suspend fun post(path: String, body: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        sessionToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }

        val json = JSONObject(body.filterValues { it != null }).toString()
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (responseCode !in 200..299) {
            throw RuntimeException("chatly $responseCode: $text")
        }
        val parsed = JSONObject(text)
        parsed.optString("sessionToken").takeIf { it.isNotEmpty() }?.let { sessionToken = it }
        parsed.toMap()
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        for (key in keys()) out[key] = get(key)
        return out
    }
}

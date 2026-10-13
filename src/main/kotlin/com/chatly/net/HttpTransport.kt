package com.chatly.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP answer: the status line's code and the raw body, never parsed here. */
public data class HttpResponse(
    val status: Int,
    val body: String,
) {
    public val ok: Boolean get() = status in 200..299
}

/**
 * The SDK's whole network surface, behind an interface.
 *
 * Extracted from [com.chatly.Chatly] for one reason: a test has to be able to
 * assert on **what the SDK sent**. Every request-shape bug this package
 * shipped — `channelPublicId` for `publicId`, a missing `visitorToken`, a
 * bearer header the widget surface has never read — is invisible to a test
 * that only looks at responses, because zod STRIPS an unknown key and the
 * server answers 200 having done something else. See
 * `docs/prompts/5/mobile-sdk-visitor-contract.md` §4.
 */
public interface HttpTransport {
    /** A JSON POST. Returns the response even for a non-2xx; it never throws on status. */
    public suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    /**
     * Raw bytes to a presigned storage URL — step 2 of the three-step upload.
     *
     * Not multipart and not base64: the bytes, with every header the presign
     * response handed back. [method] comes from that response too (`PUT`
     * today) rather than being assumed here.
     */
    public suspend fun uploadBytes(
        url: String,
        method: String,
        headers: Map<String, String>,
        bytes: ByteArray,
    ): HttpResponse
}

/**
 * The default transport: `HttpURLConnection`, so the package keeps its "no
 * HTTP dependency" property. Everything runs on [Dispatchers.IO].
 */
public class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {

    override suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String>,
    ): HttpResponse = send(url, "POST", headers + ("Content-Type" to "application/json"), json.toByteArray(Charsets.UTF_8))

    override suspend fun uploadBytes(
        url: String,
        method: String,
        headers: Map<String, String>,
        bytes: ByteArray,
    ): HttpResponse = send(url, method.uppercase(), headers, bytes)

    private suspend fun send(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HttpResponse(status, text)
        } finally {
            conn.disconnect()
        }
    }
}

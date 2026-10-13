package com.chatly.testing

import com.chatly.net.HttpResponse
import com.chatly.net.HttpTransport
import com.chatly.realtime.RealtimeSocket
import com.chatly.realtime.RealtimeSocketFactory
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One recorded request, as the SDK actually sent it. */
public data class SentRequest(
    val url: String,
    val body: String,
    val headers: Map<String, String>,
) {
    val json: JSONObject get() = JSONObject(body)
    val path: String get() = url.substringAfter("https://api.test")
    fun has(key: String): Boolean = json.has(key)
    fun str(key: String): String? = if (json.has(key)) json.get(key).toString() else null
}

/**
 * A transport that records what it was handed and answers from a canned table.
 *
 * It deliberately does NOT echo the request. A fake that answers every route
 * with whatever the SDK asked for agrees with the bug — the trap
 * `docs/prompts/5/mobile-sdk-visitor-contract.md` §4 names, and the one that
 * has cost this repo a day twice. Every assertion in these suites is on
 * [sent], which is the half a fake cannot fabricate.
 */
public class RecordingTransport(
    /** Path → response body. A path with no entry is a hard failure, not a 200. */
    private val responses: Map<String, (Int) -> String>,
) : HttpTransport {

    public val sent: MutableList<SentRequest> = CopyOnWriteArrayList()
    public val uploads: MutableList<Triple<String, String, Int>> = CopyOnWriteArrayList()
    private val counts = mutableMapOf<String, AtomicInteger>()

    public fun forPath(path: String): List<SentRequest> = sent.filter { it.path == path }

    public fun lastFor(path: String): SentRequest =
        forPath(path).lastOrNull() ?: error("the SDK never called $path; it called ${sent.map { it.path }}")

    override suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String>,
    ): HttpResponse {
        val request = SentRequest(url, json, headers)
        sent.add(request)
        val body = responses[request.path]
            ?: return HttpResponse(404, """{"error":{"code":"no_fake_for_${request.path}"}}""")
        val n = counts.getOrPut(request.path) { AtomicInteger(0) }.incrementAndGet()
        return HttpResponse(200, body(n))
    }

    override suspend fun uploadBytes(
        url: String,
        method: String,
        headers: Map<String, String>,
        bytes: ByteArray,
    ): HttpResponse {
        uploads.add(Triple(url, method, bytes.size))
        return HttpResponse(200, "")
    }
}

/** One socket the SDK opened, and the handshake it opened it with. */
public class FakeSocket(
    public val url: String,
    public val auth: Map<String, String>,
) : RealtimeSocket {
    public var connected: Boolean = false
        private set
    public var closed: Boolean = false
        private set
    public val published: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    private var onConnect: (() -> Unit)? = null
    private var onEvent: ((String) -> Unit)? = null
    private var onDisconnect: ((String?) -> Unit)? = null

    override fun onConnect(handler: () -> Unit) {
        onConnect = handler
    }

    override fun onEvent(handler: (String) -> Unit) {
        onEvent = handler
    }

    override fun onDisconnect(handler: (String?) -> Unit) {
        onDisconnect = handler
    }

    override fun emit(event: String, payloadJson: String) {
        published.add(event to payloadJson)
    }

    override fun connect() {
        connected = true
        onConnect?.invoke()
    }

    override fun close() {
        closed = true
        connected = false
    }

    /** Pretend the transport dropped, which is what should trigger a re-mint. */
    public fun drop(reason: String = "transport close") {
        connected = false
        onDisconnect?.invoke(reason)
    }

    /** Deliver one inbound envelope. */
    public fun deliver(rawJson: String) {
        onEvent?.invoke(rawJson)
    }
}

/** Records every socket the SDK opened, in order. */
public class RecordingSocketFactory : RealtimeSocketFactory {
    public val opened: MutableList<FakeSocket> = CopyOnWriteArrayList()

    override fun open(url: String, auth: Map<String, String>): RealtimeSocket =
        FakeSocket(url, auth).also { opened.add(it) }
}

/**
 * Poll until [predicate] holds or the deadline passes.
 *
 * The reconnect path is a `delay` on a background scope, so a test that
 * asserted immediately would race it. Fails loudly on timeout rather than
 * passing because nothing happened.
 */
public fun awaitTrue(timeoutMs: Long = 2_000, what: String, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return
        Thread.sleep(5)
    }
    error("timed out after ${timeoutMs}ms waiting for: $what")
}

package com.chatly

import com.chatly.models.KbArticle
import com.chatly.models.KbArticleResponse
import com.chatly.models.KbResult
import com.chatly.models.KbSearchResponse
import org.json.JSONObject

/**
 * Knowledge-base reads, as extension functions on [Chatly].
 *
 * Moved out of `Chatly.kt` when that file crossed the 650-line ceiling that
 * `scripts/check-file-size.mjs` enforces. The seam is honest: both calls are
 * stateless reads that touch no conversation, no socket and no session
 * beyond an optional visitor token, which is why `visitorToken` is sent with
 * `putIfNotNull` here and required almost everywhere else.
 *
 * Extensions rather than a second class, so `chatly.searchKb(...)` keeps
 * working for every consumer — moving them would have been a breaking change
 * to a published package for a line count. `storage`, `json` and `post` were
 * widened from `private` to `internal` to make this possible: `internal` is
 * module-visible and is NOT part of the API surface a consumer compiles
 * against, so nothing outside this module gained access.
 */
/** `POST /v1/widget/kb/search`. `visitorToken` is optional here. */
public suspend fun Chatly.searchKb(query: String, limit: Int = 8): List<KbResult> {
    val body = JSONObject().apply {
        put("publicId", channelPublicId)
        putIfNotNull("visitorToken", storage.read())
        put("query", query)
        put("limit", limit)
        put("locale", java.util.Locale.getDefault().toLanguageTag())
    }
    return json.decodeFromString(
        KbSearchResponse.serializer(),
        post("/v1/widget/kb/search", body),
    ).results
}

/** `POST /v1/widget/kb/article`. */
public suspend fun Chatly.kbArticle(slug: String): KbArticle {
    val body = JSONObject().apply {
        put("publicId", channelPublicId)
        putIfNotNull("visitorToken", storage.read())
        put("slug", slug)
    }
    return json.decodeFromString(
        KbArticleResponse.serializer(),
        post("/v1/widget/kb/article", body),
    ).article
}

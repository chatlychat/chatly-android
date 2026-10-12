package com.chatly.models

import kotlinx.serialization.Serializable

/** Full KB article body, rendered server-side. */
@Serializable
public data class KbArticle(
    val id: String,
    val slug: String,
    val title: String,
    val body: String,
    val bodyHtml: String? = null,
    val updatedAt: String,
)

/** Wrapper for `/v1/widget/kb/article`. */
@Serializable
public data class KbArticleResponse(
    val article: KbArticle,
)

package com.chatly.models

import kotlinx.serialization.Serializable

/** Single KB search hit. */
@Serializable
public data class KbResult(
    val id: String,
    val slug: String,
    val title: String,
    val excerpt: String,
)

/** Wrapper for `/v1/widget/kb/search`. */
@Serializable
public data class KbSearchResponse(
    val results: List<KbResult> = emptyList(),
)

package com.chatly.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Identity payload sent during [com.chatly.Chatly.initSession].
 *
 * Mirrors `WidgetIdentity` in `apps/widget/src/client.ts`.
 *
 * @property externalId Customer's user identifier in their system.
 * @property email Optional email address.
 * @property name Optional display name.
 * @property userHash HMAC of [externalId] for identity verification, when enabled.
 * @property attributes Arbitrary contact attributes (JSON-serialisable).
 */
@Serializable
public data class ChatlyIdentity(
    val externalId: String,
    val email: String? = null,
    val name: String? = null,
    val userHash: String? = null,
    val attributes: Map<String, JsonElement> = emptyMap(),
)

package com.chatly.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Identity payload sent during [com.chatly.Chatly.initSession].
 *
 * Mirrors `WidgetIdentity` in `apps/widget/src/client.ts`.
 *
 * @property externalId Customer's user identifier in their system. Optional —
 *   an email-only identity is a coherent thing to send and the server accepts
 *   it (`widgetInitSchema`), it just cannot be VERIFIED, because `userHash`
 *   binds the external id alone. At least one of [externalId] / [email] must
 *   be present or the server refuses the identity.
 * @property email Optional email address.
 * @property name Optional display name.
 * @property userHash HMAC-SHA256 of [externalId] with the channel's identity
 *   secret, required on channels with identity verification enabled. Computed
 *   on your backend — never in the app.
 * @property attributes Arbitrary contact attributes (JSON-serialisable).
 */
@Serializable
public data class ChatlyIdentity(
    val externalId: String? = null,
    val email: String? = null,
    val name: String? = null,
    val userHash: String? = null,
    val attributes: Map<String, JsonElement> = emptyMap(),
)

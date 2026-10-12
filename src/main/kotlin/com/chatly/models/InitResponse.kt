package com.chatly.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Session block returned by `/v1/widget/init`. */
@Serializable
public data class WidgetSession(
    val token: String,
    val contactId: String,
    val workspaceId: String,
    val brandId: String,
    val channelId: String,
)

/** Workspace summary returned by `/v1/widget/init`. */
@Serializable
public data class WidgetWorkspace(
    val id: String,
    val name: String,
)

/**
 * Response payload for `/v1/widget/init`.
 *
 * @property session Visitor session credentials, persisted by the SDK.
 * @property workspace Workspace owning the widget channel.
 * @property branding Free-form branding map (logo, colors, etc.).
 */
@Serializable
public data class InitResponse(
    val session: WidgetSession,
    val workspace: WidgetWorkspace,
    val branding: Map<String, JsonElement> = emptyMap(),
)

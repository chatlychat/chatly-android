package com.chatly.realtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Protocol version pinned in `packages/shared/src/realtime/index.ts`. */
public const val REALTIME_PROTOCOL_VERSION: Int = 1

/**
 * Wire envelope shared with browser / Node / iOS clients.
 *
 * Mirrors `realtimeEnvelopeSchema` in `packages/shared/src/realtime/index.ts`.
 * `workspace_id` is snake-cased over the wire; we keep [workspaceId] camelCase
 * in Kotlin and re-map via [SerialName].
 *
 * @property v Protocol version, currently [REALTIME_PROTOCOL_VERSION].
 * @property type Event discriminator (see `realtimeEventTypeSchema`).
 * @property id UUID v7 of the event — usable as a replay cursor.
 * @property ts Server timestamp (epoch ms).
 * @property workspaceId Workspace UUID v7 (snake_cased on the wire).
 * @property payload Type-specific JSON payload.
 */
@Serializable
public data class RealtimeEnvelope(
    val v: Int = REALTIME_PROTOCOL_VERSION,
    val type: String,
    val id: String,
    val ts: Long,
    @SerialName("workspace_id") val workspaceId: String,
    val payload: JsonElement = JsonNull,
)

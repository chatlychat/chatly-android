package com.chatly.models

import kotlinx.serialization.Serializable

/** Conversation lifecycle status. */
public enum class ConversationStatus {
    open, snoozed, closed, pending;
}

/** Lightweight conversation summary for inbox lists. */
@Serializable
public data class ConversationSummary(
    val id: String,
    val status: String,
    val subject: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val unreadContactCount: Int = 0,
    val createdAt: String,
)

/** Response for `/v1/widget/conversations` (start). */
@Serializable
public data class StartResponseConversation(
    val id: String,
)

/** Message block returned by `/v1/widget/conversations`. */
@Serializable
public data class StartResponseMessage(
    val id: String,
    val body: String,
    val createdAt: String,
)

/** Response for `/v1/widget/conversations`. */
@Serializable
public data class StartResponse(
    val conversation: StartResponseConversation,
    val message: StartResponseMessage,
)

/** Wrapper for `/v1/widget/conversations/list`. */
@Serializable
public data class ConversationListResponse(
    val conversations: List<ConversationSummary> = emptyList(),
)

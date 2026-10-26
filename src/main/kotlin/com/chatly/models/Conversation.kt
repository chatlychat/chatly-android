package com.chatly.models

import kotlinx.serialization.Serializable

/**
 * Conversation lifecycle status.
 *
 * FIVE states. `merged` was missing: the server's `conversationStatusSchema`
 * has had it all along, `POST /v1/widget/conversations/list` applies no status
 * filter, and an agent merging two of a contact's threads sets the loser to
 * `merged` — an ordinary thing to do to a visitor who opened the chat twice.
 * So this enum could not name a state the API returns.
 *
 * Note that [ConversationSummary.status] is a `String`, not this type, so
 * nothing in the SDK decodes through here and no response ever failed over
 * it. That is the only reason the gap was harmless: it is a public type a
 * consumer reaches for, and it was missing a value they would receive.
 * [parse] is what makes it usable for that.
 */
public enum class ConversationStatus {
    open, snoozed, closed, pending,

    /**
     * Merged into another conversation. A tombstone — the messages live on
     * the winner now, and the API answers a conflict rather than a no-op if
     * you try to close it.
     */
    merged;

    /**
     * Whether a visitor may keep writing into a thread in this state.
     *
     * One place, so a state added later is decided once rather than by
     * whichever `!=` a caller happened to write. False for `merged` although
     * it is not closed — which is exactly the distinction a `!= closed` test
     * cannot make, and the bug the Flutter and Swift clients both had.
     */
    public val isResumable: Boolean
        get() = this == open || this == pending || this == snoozed

    public companion object {
        /**
         * Decodes a wire value, or null when this build does not know it.
         *
         * Null rather than a fallback to [open]: a state we cannot name is
         * one a caller must not write into, and every other mobile SDK's
         * fallback-to-open is what let `merged` masquerade as a live thread.
         */
        public fun parse(raw: String?): ConversationStatus? =
            ConversationStatus.entries.firstOrNull { it.name == raw }
    }
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

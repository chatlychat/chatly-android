package com.chatly

import com.chatly.models.ConversationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ConversationStatus` against the server's own set.
 *
 * ## What was wrong
 *
 * It listed four of the five states in `conversationStatusSchema`. `merged`
 * was missing, and `POST /v1/widget/conversations/list` applies no status
 * filter, so a visitor whose two threads an agent merged receives a row this
 * enum could not name.
 *
 * Nothing failed over it, and the reason is worth knowing: `ConversationSummary.status`
 * is a `String`, so the enum is a public type the SDK itself never decodes
 * through. A consumer reaching for it was the only one who would have hit the
 * gap — and the same gap in `sdk-swift`, where the enum IS the decoded type,
 * threw a `DecodingError` for the whole response.
 *
 * ## Why the server side is written out here
 *
 * The definition is TypeScript in `packages/shared`, which Kotlin cannot
 * import, and this module's only CI is its published mirror where that path
 * does not exist. So the five names below are copied from
 * `packages/shared/src/schemas/conversation.ts`, and a monorepo-side gate
 * that reads that file is the missing other half.
 */
class WireVocabularyTest {
    /** `conversationStatusSchema`, verbatim. */
    private val wire = listOf("open", "pending", "snoozed", "closed", "merged")

    @Test
    fun `every server status has a member`() {
        // The floor: five, so a list that loses entries fails here rather
        // than passing over a shorter loop.
        assertTrue(wire.size >= 5, "the server list lost entries")
        for (raw in wire) {
            assertEquals(raw, ConversationStatus.parse(raw)?.name, raw)
        }
    }

    @Test
    fun `the enum holds exactly the server set and nothing invented`() {
        assertEquals(wire.toSet(), ConversationStatus.entries.map { it.name }.toSet())
    }

    @Test
    fun `an unknown status is null rather than silently open`() {
        // Deliberately not a fallback. Falling back to `open` is what let
        // `merged` masquerade as a live thread in the Flutter client.
        assertNull(ConversationStatus.parse("a_state_from_2027"))
        assertNull(ConversationStatus.parse(null))
    }

    @Test
    fun `isResumable is false for the states that are not`() {
        assertFalse(ConversationStatus.merged.isResumable)
        assertFalse(ConversationStatus.closed.isResumable)
        assertTrue(ConversationStatus.open.isResumable)
        assertTrue(ConversationStatus.pending.isResumable)
        assertTrue(ConversationStatus.snoozed.isResumable)
    }
}

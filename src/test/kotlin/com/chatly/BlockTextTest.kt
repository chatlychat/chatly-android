package com.chatly

import com.chatly.blocks.ChatlyBlock
import com.chatly.blocks.ChatlyBlockText
import com.chatly.blocks.ChatlyCardBadge
import com.chatly.blocks.ChatlyField
import com.chatly.blocks.ChatlyMoney
import com.chatly.blocks.ChatlyTableColumn
import com.chatly.blocks.ChatlyTableRow
import com.chatly.blocks.ChatlyTimelineStep
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a block SAYS. The View code around it is only compile-checked (there
 * is no Android framework on a JVM test run), so these are the words a
 * shopper actually reads.
 */
class BlockTextTest {

    @Test
    fun `money divides by the currency's own minor unit, not by 100`() {
        // USD has two digits, JPY none, KWD three. `amount / 100` is perfect
        // in USD and wrong by two orders of magnitude in opposite directions
        // for the other two, which is why it would ship.
        assertEquals("\$48.20", ChatlyBlockText.formatMoney(ChatlyMoney(4820, "USD"), Locale.US))
        val jpy = ChatlyBlockText.formatMoney(ChatlyMoney(4820, "JPY"), Locale.US)
        assertTrue(jpy.contains("4,820"), "JPY 4820 is ¥4,820, got $jpy")
        val kwd = ChatlyBlockText.formatMoney(ChatlyMoney(4820, "KWD"), Locale.US)
        assertTrue(kwd.contains("4.820"), "KWD 4820 is 4.820 KWD, got $kwd")
    }

    @Test
    fun `an unknown ISO code falls back instead of throwing`() {
        // The reader admits any three upper-case letters, so this is reachable.
        assertEquals("ZZZ 48.20", ChatlyBlockText.formatMoney(ChatlyMoney(4820, "ZZZ"), Locale.US))
    }

    @Test
    fun `a was-price renders only when it is above the price`() {
        assertEquals(
            listOf("\$48.20", "Was \$60.00"),
            ChatlyBlockText.priceLines(ChatlyMoney(4820, "USD", 6000)).map { line ->
                line.replace(' ', ' ')
            },
        )
        assertEquals(1, ChatlyBlockText.priceLines(ChatlyMoney(4820, "USD", 4000)).size)
        assertEquals(1, ChatlyBlockText.priceLines(ChatlyMoney(4820, "USD", 4820)).size)
    }

    @Test
    fun `a timeline row names its state in words`() {
        assertEquals(
            "Packed — Done",
            ChatlyBlockText.stepLine(ChatlyTimelineStep("Packed", "done")),
        )
        assertEquals(
            "Out for delivery — In progress",
            ChatlyBlockText.stepLine(ChatlyTimelineStep("Out for delivery", "current")),
        )
        // No dangling separator when the step has no time.
        assertEquals(
            "Delivered — Pending",
            ChatlyBlockText.stepLine(ChatlyTimelineStep("Delivered", "pending")),
        )
        val dated = ChatlyBlockText.stepLine(
            ChatlyTimelineStep("Packed", "done", "2026-10-20T10:00:00.000Z"),
        )
        assertTrue(dated.startsWith("Packed — Done · "), dated)
        // Not asserting the date itself: `DateFormat` follows the JVM default
        // locale AND calendar, and a Buddhist-calendar default renders a
        // different year for the same instant.
        assertTrue(dated.length > "Packed — Done · ".length, dated)
    }

    @Test
    fun `the ISO shapes the API serves all parse, and prose does not`() {
        for (at in listOf(
            "2026-10-20T10:00:00.000Z",
            "2026-10-20T10:00:00Z",
            "2026-10-20T10:00:00.000+02:00",
            "2026-10-20T10:00:00",
            "2026-10-20",
        )) {
            assertTrue(ChatlyBlockText.parseIsoDate(at) != null, "should parse $at")
        }
        for (at in listOf("", "yesterday", "soon", "2026-13-45", "not a date")) {
            assertNull(ChatlyBlockText.parseIsoDate(at), "should refuse $at")
        }
        assertEquals("", ChatlyBlockText.formatTime("yesterday"))
    }

    @Test
    fun `a badge carries its tone as a glyph and as a word`() {
        assertEquals("✓ Great price", ChatlyBlockText.badgeText(ChatlyCardBadge("Great price", "success")))
        assertEquals("! Above typical", ChatlyBlockText.badgeText(ChatlyCardBadge("Above typical", "warning")))
        assertEquals("✕ Final sale", ChatlyBlockText.badgeText(ChatlyCardBadge("Final sale", "danger")))
        // `neutral` is the ABSENCE of a claim: no glyph, and nothing spoken.
        assertEquals("Sale", ChatlyBlockText.badgeText(ChatlyCardBadge("Sale", "neutral")))
        assertEquals("Sale", ChatlyBlockText.badgeDescription(ChatlyCardBadge("Sale", "neutral")))
        assertEquals(
            "Great price (positive)",
            ChatlyBlockText.badgeDescription(ChatlyCardBadge("Great price", "success")),
        )
    }

    @Test
    fun `stock, fields, place and status`() {
        assertEquals("In stock", ChatlyBlockText.stockLabel("in_stock"))
        assertEquals("Low stock", ChatlyBlockText.stockLabel("low"))
        assertEquals("Out of stock", ChatlyBlockText.stockLabel("out_of_stock"))
        assertNull(ChatlyBlockText.stockLabel(null))
        assertEquals("Weight: 240g", ChatlyBlockText.fieldLine(ChatlyField("Weight", "240g")))
        assertEquals("Aisle 7", ChatlyBlockText.placeLine(ChatlyBlock.Place("Aisle 7")))
        assertEquals(
            "Aisle 7 — back wall",
            ChatlyBlockText.placeLine(ChatlyBlock.Place("Aisle 7", "back wall")),
        )
        assertEquals(
            "Looking through the shop",
            ChatlyBlockText.statusLine(ChatlyBlock.Status("s1", "Looking through the shop", "running")),
        )
        assertEquals(
            "Checkout — failed",
            ChatlyBlockText.statusLine(ChatlyBlock.Status("s1", "Checkout", "failed")),
        )
    }

    @Test
    fun `a short table row pads to the column count so values stay under their heading`() {
        val columns = listOf(
            ChatlyTableColumn("Alpha"),
            ChatlyTableColumn(""),
            ChatlyTableColumn("Gamma"),
        )
        assertEquals("Alpha ·  · Gamma", ChatlyBlockText.tableHeaderLine(columns))
        assertEquals(
            "Price: 1 · 2 · 3",
            ChatlyBlockText.tableRowLine(ChatlyTableRow("Price", listOf("1", "2", "3")), 3),
        )
        assertEquals(
            "Weight: 240g ·  · ",
            ChatlyBlockText.tableRowLine(ChatlyTableRow("Weight", listOf("240g")), 3),
        )
    }
}

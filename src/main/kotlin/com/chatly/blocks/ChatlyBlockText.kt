package com.chatly.blocks

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

/**
 * Every string a block renders, with no Android import anywhere.
 *
 * Deliberately separate from the View code in `com.chatly.ui.blocks`. The
 * JUnit suite runs on a bare JVM with no Android framework, so anything that
 * touches `android.*` is untestable here and only compile-checked by the
 * Gradle build. Keeping the words — prices, timeline lines, stock labels,
 * badge glyphs, table lines — on this side of that seam is what makes them
 * coverable, and they are the half a shopper actually reads.
 *
 * English literals, because this package has no translation layer. A
 * consumer who needs another language reads [ChatlyBlocks.read] and renders
 * the blocks themselves, which is what that function is public for.
 */
public object ChatlyBlockText {

    /** The timeline states, in words. A coloured dot alone says nothing to TalkBack. */
    public fun stateLabel(state: String): String = when (state) {
        "done" -> "Done"
        "current" -> "In progress"
        else -> "Pending"
    }

    /** Availability in this surface's own words, or null when unstated. */
    public fun stockLabel(stock: String?): String? = when (stock) {
        "in_stock" -> "In stock"
        "low" -> "Low stock"
        "out_of_stock" -> "Out of stock"
        else -> null
    }

    /**
     * What each tone adds BESIDES a colour, and why it has to add something.
     *
     * The rule is not "pick a legible colour", it is that colour may never be
     * the only carrier of meaning: "Great price" and "Above typical" are both
     * badges and they say opposite things in one pill. So a reader with no
     * colour vision gets the glyph and a screen reader gets [toneWord].
     * `neutral` is the ABSENCE of a claim and gets neither — a plain pill is
     * the correct rendition of "Sale".
     */
    public fun toneGlyph(tone: String): String = when (tone) {
        "success" -> "✓"
        "warning" -> "!"
        "danger" -> "✕"
        else -> ""
    }

    /** The tone as a word, for a content description. Empty for `neutral`. */
    public fun toneWord(tone: String): String = when (tone) {
        "success" -> "positive"
        "warning" -> "caution"
        "danger" -> "negative"
        else -> ""
    }

    /** A badge as drawn: the glyph, then the text. */
    public fun badgeText(badge: ChatlyCardBadge): String {
        val glyph = toneGlyph(badge.tone)
        return if (glyph.isEmpty()) badge.text else "$glyph ${badge.text}"
    }

    /** A badge as SPOKEN: the text, then the tone in words. */
    public fun badgeDescription(badge: ChatlyCardBadge): String {
        val word = toneWord(badge.tone)
        return if (word.isEmpty()) badge.text else "${badge.text} ($word)"
    }

    /** One field row. */
    public fun fieldLine(field: ChatlyField): String = "${field.label}: ${field.value}"

    /**
     * One timeline row. An absent or unformattable time leaves no dangling
     * separator.
     */
    public fun stepLine(step: ChatlyTimelineStep): String {
        val time = step.at?.let { formatTime(it) }.orEmpty()
        val head = "${step.label} — ${stateLabel(step.state)}"
        return if (time.isEmpty()) head else "$head · $time"
    }

    /** A place, which is a label and maybe a detail. Never coordinates. */
    public fun placeLine(block: ChatlyBlock.Place): String =
        if (block.detail == null) block.label else "${block.label} — ${block.detail}"

    /** A status line, which says so when it failed. */
    public fun statusLine(block: ChatlyBlock.Status): String =
        if (block.state == "failed") "${block.text} — failed" else block.text

    /** The table's heading row. A coerced blank heading keeps its position. */
    public fun tableHeaderLine(columns: List<ChatlyTableColumn>): String =
        columns.joinToString(" · ") { it.label }

    /** One table row: its attribute, then a cell per column. Short rows render blanks. */
    public fun tableRowLine(row: ChatlyTableRow, columnCount: Int): String {
        val cells = (0 until columnCount).map { row.cells.getOrNull(it).orEmpty() }
        return "${row.label}: ${cells.joinToString(" · ")}"
    }

    /**
     * The price, and the was-price when there is one.
     *
     * ONE line each rather than a struck-through was-price beside it: "Was X"
     * reads correctly to a screen reader, which the visual version does not.
     */
    public fun priceLines(money: ChatlyMoney): List<String> {
        val lines = ArrayList<String>(2)
        lines.add(formatMoney(money))
        val compareAt = money.compareAt
        if (compareAt != null && compareAt > money.amount) {
            lines.add("Was " + formatMoney(ChatlyMoney(compareAt, money.currency)))
        }
        return lines
    }

    /**
     * Format one amount in the device's locale.
     *
     * ── Minor units, never a float ─────────────────────────────────────
     *
     * [ChatlyMoney.amount] is an integer count of the currency's smallest
     * unit: 4820 is $48.20, 4820 is also ¥4,820, and 4820 is 4.820 KWD.
     *
     * THE DIVISOR IS NOT 100. Three currencies have no minor unit (JPY, KRW,
     * VND) and four have three digits (KWD, BHD, JOD, OMR), so `amount / 100`
     * renders ¥4,820 as "¥48" and 4.820 KWD as "48.200 KWD" — wrong by two
     * orders of magnitude in opposite directions, and perfect in USD, which
     * is why it would ship. The digit count comes off
     * `Currency.defaultFractionDigits`, so a currency the JVM learns about
     * later is right with no table here.
     *
     * An unknown ISO code falls back to [plainMoney] rather than taking the
     * thread's render down: `Currency.getInstance` throws on a non-ISO code,
     * and `defaultFractionDigits` answers -1 for one it has no data for.
     */
    public fun formatMoney(money: ChatlyMoney, locale: Locale = Locale.getDefault()): String {
        val currency = runCatching { Currency.getInstance(money.currency) }.getOrNull()
            ?: return plainMoney(money)
        val digits = currency.defaultFractionDigits
        if (digits < 0) return plainMoney(money)
        return runCatching {
            val format = NumberFormat.getCurrencyInstance(locale)
            format.currency = currency
            format.maximumFractionDigits = digits
            format.minimumFractionDigits = digits
            format.format(BigDecimal.valueOf(money.amount).movePointLeft(digits))
        }.getOrElse { plainMoney(money) }
    }

    /** The locale-free rendition, used as the fallback and for accessibility text. */
    public fun plainMoney(money: ChatlyMoney): String =
        money.currency + " " + BigDecimal.valueOf(money.amount)
            .movePointLeft(2)
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString()

    /**
     * A milestone's timestamp in the device's locale, or `""` when this
     * string is not a date.
     *
     * The reader has already dropped an `at` it could not parse, so a
     * malformed row cannot reach here; the guard is about the string being
     * exotic rather than the value being bad.
     */
    public fun formatTime(at: String): String {
        val date = parseIsoDate(at) ?: return ""
        return runCatching {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
        }.getOrDefault("")
    }

    /**
     * An ISO-8601 timestamp, or null.
     *
     * `java.time` is API 26 and this library's `minSdk` is 24, so this is
     * `SimpleDateFormat` rather than `Instant.parse` — without core library
     * desugaring, which is the consumer's build setting and not ours to
     * require. The patterns are the shapes the API actually serves, most
     * specific first.
     */
    public fun parseIsoDate(raw: String): Date? {
        for (pattern in ISO_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(raw)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private val ISO_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
    )
}

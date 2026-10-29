package com.chatly.blocks

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bumped only on a breaking change to the envelope itself. */
public const val CHATLY_BLOCK_PROTOCOL_VERSION: Int = 1

/**
 * Read a stored block envelope, refusing what cannot be drawn.
 *
 * Mirrors `packages/sdk-react-native/src/blocks/read-envelope.ts`, which is
 * the reference implementation for the mobile ports and is itself pinned
 * against `packages/shared/src/widget-blocks/read-envelope.ts`.
 *
 * ── The contract, which is the same on every renderer ──────────────────
 *
 * Never throw, never render a hole. A row written before a field was
 * validated — or by a future server this app does not know about — must
 * produce the most of the message that CAN be drawn, not an exception and
 * not a blank space. So:
 *
 *   an unreadable BLOCK is dropped, the rest of the message renders;
 *   an unsafe card image drops the IMAGE, not the card;
 *   an unreadable price drops the PRICE, because a formatter handed a string
 *     renders nonsense at a shopper;
 *   an unknown `type` is EXPECTED rather than exceptional — an old app meets
 *     a newer server — and is dropped silently.
 *
 * This is why the field on [com.chatly.models.MessageRow] is a `JsonElement`
 * and not a typed envelope: kotlinx.serialization will decode any JSON value
 * into one, so a malformed `blocks` cannot fail the decode of the message it
 * rides on. Validation happens here, at render time.
 *
 * ── The media guard ────────────────────────────────────────────────────
 *
 * `https:` only, no control characters, no untrimmed string, length capped,
 * and a host that is actually there. A card is authored by whoever holds an
 * API key, and `http:` on a phone is a downgrade a shopper cannot see while
 * `file:` is the device's own disk.
 */
public object ChatlyBlocks {

    /**
     * Every block of [raw] that can be drawn, in order. Empty when the
     * envelope is absent, malformed, of another protocol version, or when no
     * block inside it survived. Never throws.
     */
    public fun read(raw: JsonElement?): List<ChatlyBlock> {
        val envelope = raw as? JsonObject ?: return emptyList()
        if (envelope["v"].wholeNumber()?.toInt() != CHATLY_BLOCK_PROTOCOL_VERSION) {
            return emptyList()
        }
        val blocks = envelope["blocks"] as? JsonArray ?: return emptyList()
        return blocks.mapNotNull { readBlock(it) }
    }

    /** One block, or null when it cannot be drawn. Never throws. */
    public fun readBlock(raw: JsonElement?): ChatlyBlock? {
        val obj = raw as? JsonObject ?: return null
        val id = obj["id"].str()
        return when (obj["type"].str()) {
            "text" -> obj["markdown"].str()?.let { ChatlyBlock.Text(it, id) }
            "media" -> readMedia(obj, id)
            "fields" -> readPairs(obj["items"]).takeIf { it.isNotEmpty() }
                ?.let { ChatlyBlock.Fields(it, id) }
            "card" -> readCard(obj)?.let { ChatlyBlock.Card(it) }
            "card_grid" -> readGrid(obj, id)
            "actions" -> readActions(obj["actions"]).takeIf { it.isNotEmpty() }
                ?.let { ChatlyBlock.Actions(it, id) }
            "quick_replies" -> readPairs(obj["options"]).takeIf { it.isNotEmpty() }
                ?.let { ChatlyBlock.QuickReplies(it, id) }
            // No readable step is no timeline: a bordered blank space where
            // the answer to "where is my order" belongs.
            "order_status" -> readSteps(obj["steps"]).takeIf { it.isNotEmpty() }
                ?.let { ChatlyBlock.OrderStatus(it, id) }
            "table" -> readTable(obj, id)
            "place" -> obj["label"].str()?.let {
                ChatlyBlock.Place(it, obj["detail"].str(), obj["ref"].str(), id)
            }
            // `id` is required: a status block exists to be patched.
            "status" -> if (id == null) {
                null
            } else {
                obj["text"].str()?.let { ChatlyBlock.Status(id, it, obj["state"].str()) }
            }
            "divider" -> ChatlyBlock.Divider(id)
            else -> null
        }
    }

    /** `https:` and nothing else. Mirrors `isSafeMediaSrc` in the shared guard. */
    public fun isSafeMediaSrc(src: String): Boolean = isSafeUrl(src, MEDIA_SCHEMES)

    /**
     * A url action's href: `https:`, `mailto:` or `tel:` — exactly the shared
     * schema's set, nothing wider.
     *
     * An `ACTION_VIEW` intent launches any scheme some installed app claims,
     * so the rule is an ALLOW-LIST of three: `intent:`, `sms:`, `market:` and
     * an app's own scheme are all refused. The three admitted open the
     * browser, a draft e-mail and the dialer with the number filled in; none
     * acts without the visitor confirming.
     */
    public fun isSafeActionHref(href: String): Boolean = isSafeUrl(href, ACTION_SCHEMES)

    private val MEDIA_SCHEMES = setOf("https")
    private val ACTION_SCHEMES = setOf("https", "mailto", "tel")
    private val CURRENCY = Regex("^[A-Z]{3}$")
    private val STOCK_STATES = setOf("in_stock", "low", "out_of_stock")
    private val STEP_STATES = setOf("done", "current", "pending")
    private val CARD_TAP_KINDS = setOf("url", "host_event", "step", "navigate")
    private val MORE_KINDS = setOf("postback", "url", "navigate")

    private fun isSafeUrl(raw: String, schemes: Set<String>): Boolean {
        if (raw.isEmpty() || raw.length > 2000) return false
        if (raw != raw.trim()) return false
        // Matching control characters IS the check: a URL parser strips them
        // and an allow-list applied after the strip reads the wrong string.
        if (raw.any { it.code <= 0x1F || it.code == 0x7F }) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (!schemes.contains(scheme)) return false
        // mailto: and tel: carry no host by design; an https url without one
        // is not fetchable.
        return scheme != "https" || !uri.host.isNullOrEmpty()
    }

    private fun readMedia(obj: JsonObject, id: String?): ChatlyBlock? {
        val url = obj["url"].str()
        val alt = obj["alt"].str()
        val kind = obj["kind"].str()
        // Unlike a card's image, a media block IS its image: nothing is left
        // to render once the src is refused, so the block goes.
        if (url == null || alt == null || kind == null) return null
        if (!isSafeMediaSrc(url)) return null
        if (kind != "image" && kind != "video") return null
        return ChatlyBlock.Media(kind, url, alt, id)
    }

    private fun readGrid(obj: JsonObject, id: String?): ChatlyBlock? {
        val cards = (obj["cards"] as? JsonArray)?.mapNotNull { readCard(it) } ?: emptyList()
        if (cards.isEmpty()) return null
        return ChatlyBlock.CardGrid(
            cards = cards,
            carousel = obj["layout"].str() == "carousel",
            more = readGridMore(obj["more"]),
            id = id,
        )
    }

    /**
     * A grid's trailing tile, or null.
     *
     * Narrowed to the action kinds that can page: a `copy`, `host_event` or
     * `step` tile would be a button that shows no more cards. Dropping the
     * tile keeps the grid — the cards are the substance.
     */
    private fun readGridMore(raw: JsonElement?): ChatlyGridMore? {
        val obj = raw as? JsonObject ?: return null
        val label = obj["label"].str() ?: return null
        val action = readAction(obj["action"]) ?: return null
        if (!MORE_KINDS.contains(action.kind)) return null
        return ChatlyGridMore(label, action)
    }

    private fun readCard(raw: JsonElement?): ChatlyCard? {
        val obj = raw as? JsonObject ?: return null
        val title = obj["title"].str() ?: return null
        /*
         * The pair, narrowed to the two members and DEDUPED. `['like','like']`
         * is accepted by the write schema — an enum of two with `.max(2)`
         * cannot say "distinct" — and draws two identical thumbs.
         */
        val reactions = (obj["reactions"] as? JsonArray)
            ?.mapNotNull { it.str() }
            ?.filter { it == "like" || it == "dislike" }
            ?.distinct()
            ?: emptyList()
        return ChatlyCard(
            title = title,
            id = obj["id"].str(),
            subtitle = obj["subtitle"].str(),
            image = obj["image"].str()?.takeIf { isSafeMediaSrc(it) },
            badges = readBadges(obj["badge"]),
            price = readMoney(obj["price"]),
            fields = readPairs(obj["fields"]),
            variants = readVariants(obj["variants"]),
            stock = obj["stock"].str()?.takeIf { STOCK_STATES.contains(it) },
            actions = readActions(obj["actions"]),
            reactions = reactions,
            // Only meaningful if the card OFFERS it, and read AFTER the
            // filter above so a value naming a dropped member goes with it.
            reaction = obj["reaction"].str()?.takeIf { reactions.contains(it) },
            action = readAction(obj["action"])?.takeIf { CARD_TAP_KINDS.contains(it.kind) },
        )
    }

    /**
     * Badges, collapsed to ONE shape: always `{ text, tone }`.
     *
     * `card.badge` accepts three spellings — a bare string (which is what
     * every stored row holds), an object with a `tone`, or an array of up to
     * two of either. A renderer that branched on all three would be three
     * branches, two of which nobody exercises, so the reader collapses it.
     *
     * The tone is COERCED and never dropped: it reaches a style, and dropping
     * the badge over an unknown tone would delete the words "Final sale" to
     * protect the colour of a pill.
     */
    private fun readBadges(raw: JsonElement?): List<ChatlyCardBadge> {
        val entries: List<JsonElement> = when (raw) {
            null -> emptyList()
            is JsonArray -> raw
            else -> listOf(raw)
        }
        val out = ArrayList<ChatlyCardBadge>()
        for (entry in entries) {
            val text = entry.str() ?: (entry as? JsonObject)?.get("text").str() ?: continue
            val tone = (entry as? JsonObject)?.get("tone").str()
            val known = tone == "success" || tone == "warning" || tone == "danger"
            out.add(ChatlyCardBadge(text, if (known && tone != null) tone else "neutral"))
        }
        return out
    }

    /** Read a price, or null. Refuses what cannot be drawn. */
    private fun readMoney(raw: JsonElement?): ChatlyMoney? {
        val obj = raw as? JsonObject ?: return null
        val amount = obj["amount"].wholeNumber() ?: return null
        val currency = obj["currency"].str() ?: return null
        if (!CURRENCY.matches(currency)) return null
        return ChatlyMoney(amount, currency, obj["compareAt"].wholeNumber())
    }

    /**
     * Variants, normalising the one field a renderer branches on.
     *
     * A variant needs both halves for the same reason a quick reply does:
     * `label` is what the shopper reads and `value` is what gets sent. One
     * bad option loses that option, not the card.
     *
     * `inStock` is forced to absent-or-boolean: the string `"false"` passed
     * through is truthy to one renderer and not equal to `false` in another,
     * so two obvious implementations would disagree about whether the option
     * can be bought.
     */
    private fun readVariants(raw: JsonElement?): List<ChatlyCardVariant> {
        val arr = raw as? JsonArray ?: return emptyList()
        val out = ArrayList<ChatlyCardVariant>()
        for (entry in arr) {
            val obj = entry as? JsonObject ?: continue
            val label = obj["label"].str() ?: continue
            val value = obj["value"].str() ?: continue
            out.add(ChatlyCardVariant(label, value, obj["inStock"].boolOrNull()))
        }
        return out
    }

    private fun readActions(raw: JsonElement?): List<ChatlyBlockAction> {
        val arr = raw as? JsonArray ?: return emptyList()
        return arr.mapNotNull { readAction(it) }
    }

    private fun readAction(raw: JsonElement?): ChatlyBlockAction? {
        val obj = raw as? JsonObject ?: return null
        val label = obj["label"].str() ?: return null
        val kind = obj["kind"].str() ?: return null
        val value = when (kind) {
            // Opened with an ACTION_VIEW intent: the schema's three schemes only.
            "url" -> obj["href"].str()?.takeIf { isSafeActionHref(it) }
            "postback" -> obj["value"].str()
            "host_event" -> obj["event"].str()
            "step" -> obj["canvasId"].str()
            "copy" -> obj["text"].str()
            "navigate" -> obj["path"].str()
            else -> null
        } ?: return null
        val data = (obj["payload"] ?: obj["data"] ?: obj["params"]) as? JsonObject
        return ChatlyBlockAction(
            kind = kind,
            label = label,
            value = value,
            id = obj["id"].str(),
            silent = obj["silent"].boolOrNull() == true,
            data = data,
        )
    }

    private fun readPairs(raw: JsonElement?): List<ChatlyField> {
        val arr = raw as? JsonArray ?: return emptyList()
        val out = ArrayList<ChatlyField>()
        for (entry in arr) {
            val obj = entry as? JsonObject ?: continue
            val label = obj["label"].str() ?: continue
            val value = obj["value"].str() ?: continue
            out.add(ChatlyField(label, value))
        }
        return out
    }

    /**
     * Timeline steps, with the state COERCED rather than the step dropped.
     *
     * Dropping a step whose state a later release invented (`failed`, say)
     * removes a ROW from a timeline, and a timeline missing a row reads as an
     * order that skipped a stage: the words "Payment failed" vanish and the
     * shopper is told nothing. Coercing to `pending` keeps the label and the
     * timestamp, and costs only the shape of a dot.
     *
     * `at` is dropped unless it parses, because a date a formatter cannot
     * read renders as nonsense beside a fulfilment milestone — and `at` is
     * optional precisely because a pending step has none.
     */
    private fun readSteps(raw: JsonElement?): List<ChatlyTimelineStep> {
        val arr = raw as? JsonArray ?: return emptyList()
        val out = ArrayList<ChatlyTimelineStep>()
        for (entry in arr) {
            val obj = entry as? JsonObject ?: continue
            val label = obj["label"].str() ?: continue
            val state = obj["state"].str()?.takeIf { STEP_STATES.contains(it) } ?: "pending"
            val at = obj["at"].str()?.takeIf { ChatlyBlockText.parseIsoDate(it) != null }
            out.add(ChatlyTimelineStep(label, state, at))
        }
        return out
    }

    /**
     * A comparison table.
     *
     * ── Why a bad COLUMN is coerced and a bad ROW is dropped ────────────
     *
     * Not the same mistake. A row is identified by its own label, so a row
     * without one is a line of values nobody can interpret and it goes. A
     * COLUMN is identified by its POSITION: drop column 1 and every row's
     * cells shift left, so "USD 120.00" is drawn under the wrong product — a
     * wrong answer rather than a missing one. So an unreadable heading is
     * coerced to an empty one and keeps its place.
     *
     * A table with no readable heading at all is dropped: the headings are
     * what make it a comparison rather than a list. A LONGER row is truncated
     * (a seventh cell under no heading is a column the reader invented) and a
     * SHORT row is deliberately fine and renders blanks.
     */
    private fun readTable(obj: JsonObject, id: String?): ChatlyBlock? {
        val rawColumns = obj["columns"] as? JsonArray ?: return null
        val columns = rawColumns.map { entry ->
            val column = entry as? JsonObject
            ChatlyTableColumn(
                label = column?.get("label").str() ?: "",
                image = column?.get("image").str()?.takeIf { isSafeMediaSrc(it) },
            )
        }
        if (columns.none { it.label.isNotEmpty() }) return null
        val rows = ArrayList<ChatlyTableRow>()
        for (entry in (obj["rows"] as? JsonArray ?: emptyList<JsonElement>())) {
            val row = entry as? JsonObject ?: continue
            val label = row["label"].str() ?: continue
            val cells = (row["cells"] as? JsonArray ?: emptyList<JsonElement>())
                .take(columns.size)
                .map { it.str() ?: "" }
            rows.add(ChatlyTableRow(label, cells))
        }
        if (rows.isEmpty()) return null
        return ChatlyBlock.Table(columns, rows, obj["caption"].str(), id)
    }
}

/** A non-empty JSON string, or null. A number, a bool and `null` are not strings. */
private fun JsonElement?.str(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.takeIf { it.isNotEmpty() }
}

/** A non-negative JSON integer, or null. Rejects a quoted number and a fraction. */
private fun JsonElement?.wholeNumber(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val value = primitive.content.toLongOrNull() ?: return null
    return if (value >= 0) value else null
}

/** A real JSON boolean, or null. The STRING `"false"` is not one. */
private fun JsonElement?.boolOrNull(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

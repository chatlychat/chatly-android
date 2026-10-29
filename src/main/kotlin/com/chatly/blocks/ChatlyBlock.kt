package com.chatly.blocks

import kotlinx.serialization.json.JsonObject

/**
 * The rich-block vocabulary, restated for the published Android SDK.
 *
 * Mirrors `packages/shared/src/widget-blocks/schema.ts` (which is zod, and
 * zod is not available here) and the hand-written mirror in
 * `packages/sdk-react-native/src/blocks/types.ts`, which is the REFERENCE
 * implementation for every mobile port. The canonical definition is the
 * schema; [ChatlyBlocks] accepts and refuses what the React Native reader
 * accepts and refuses.
 *
 * ── The documented subset ──────────────────────────────────────────────
 *
 * All twelve block types the reference renders are here: text, media,
 * fields, card, card_grid, actions, quick_replies, order_status, table,
 * place, status, divider.
 *
 * TWO fields are deliberately absent: `card.model` (a 3D model) and
 * `card_grid.placement` (how a world arranges cards). Both are spatial hints
 * that no 2D surface draws, kept by the React Native reader only so an app
 * embedding a 3D world can read them. There is no world host inside an
 * Android chat bubble, so they are dropped rather than carried.
 */
public sealed interface ChatlyBlock {
    /** The author's own id for this block, when it set one. */
    public val id: String?

    /** Markdown source, rendered as plain text — see [ChatlyBlock.Text]. */
    public data class Text(
        val markdown: String,
        override val id: String? = null,
    ) : ChatlyBlock

    /** An image or a video. [kind] is `image` or `video` and nothing else. */
    public data class Media(
        val kind: String,
        val url: String,
        val alt: String,
        override val id: String? = null,
    ) : ChatlyBlock

    /** A label/value list. */
    public data class Fields(
        val items: List<ChatlyField>,
        override val id: String? = null,
    ) : ChatlyBlock

    /** One card. Its id is the card's own. */
    public data class Card(val card: ChatlyCard) : ChatlyBlock {
        override val id: String? get() = card.id
    }

    /**
     * Several cards. [carousel] is the only layout distinction a phone makes:
     * two cards side by side on a phone are narrower than the images they
     * carry, so a non-carousel grid is a vertical stack.
     */
    public data class CardGrid(
        val cards: List<ChatlyCard>,
        val carousel: Boolean = false,
        val more: ChatlyGridMore? = null,
        override val id: String? = null,
    ) : ChatlyBlock

    /** A row of buttons. */
    public data class Actions(
        val actions: List<ChatlyBlockAction>,
        override val id: String? = null,
    ) : ChatlyBlock

    /**
     * Tappable canned replies. Each option's `label` is what the visitor
     * reads and `value` is what gets SENT as their message.
     */
    public data class QuickReplies(
        val options: List<ChatlyField>,
        override val id: String? = null,
    ) : ChatlyBlock

    /**
     * A read-only fulfilment timeline — "where is my order", answered in the
     * thread. Nothing in it is a control: no action, no amount, no payment
     * reference.
     */
    public data class OrderStatus(
        val steps: List<ChatlyTimelineStep>,
        override val id: String? = null,
    ) : ChatlyBlock

    /** A comparison table: the things across, their attributes down. */
    public data class Table(
        val columns: List<ChatlyTableColumn>,
        val rows: List<ChatlyTableRow>,
        val caption: String? = null,
        override val id: String? = null,
    ) : ChatlyBlock

    /**
     * A named destination, in words — never coordinates. A phone has no
     * world to travel in, so the label IS the block and there is no button.
     */
    public data class Place(
        val label: String,
        val detail: String? = null,
        val ref: String? = null,
        override val id: String? = null,
    ) : ChatlyBlock

    /** Progress the visitor is watching happen. [id] is required: it is patched. */
    public data class Status(
        override val id: String,
        val text: String,
        val state: String? = null,
    ) : ChatlyBlock

    /** A hairline. */
    public data class Divider(override val id: String? = null) : ChatlyBlock
}

/** One label/value pair — a field row, or a quick reply's label and payload. */
public data class ChatlyField(val label: String, val value: String)

/**
 * A price. [amount] and [compareAt] are MINOR units — 4820 is $48.20, and
 * also ¥4,820, and also 4.820 KWD. See [ChatlyBlockText.formatMoney] for why
 * the divisor is not 100.
 */
public data class ChatlyMoney(
    val amount: Long,
    /** ISO 4217, upper case. */
    val currency: String,
    /** The was-price. Rendered only when above [amount]. */
    val compareAt: Long? = null,
)

/**
 * A pressed button, flattened to ONE type across all six action kinds.
 *
 * [kind] is `url`, `postback`, `host_event`, `step`, `copy` or `navigate`,
 * and [value] is that kind's own target: the href, the postback value, the
 * event name, the canvas id, the text to copy, or the path. A host switches
 * on [kind]; a sealed hierarchy of six would be six branches for a callback
 * that mostly forwards.
 */
public data class ChatlyBlockAction(
    val kind: String,
    val label: String,
    val value: String,
    /** The author's own action id, where the kind carries one. */
    val id: String? = null,
    /** `postback.silent` — a press that writes no visitor message. */
    val silent: Boolean = false,
    /** `postback.payload` / `host_event.data` / `step.params`, untouched. */
    val data: JsonObject? = null,
)

/**
 * What a badge MEANS, not what colour it is: "Great price" and "Above
 * typical" are both badges and they say opposite things.
 *
 * [tone] is always one of `success`, `warning`, `danger`, `neutral` — the
 * reader coerces anything else, because an absent tone would leave every
 * renderer picking its own default.
 */
public data class ChatlyCardBadge(val text: String, val tone: String)

/**
 * One purchasable option on a card — a size, a colour, a plan tier.
 *
 * [inStock] is tri-state on purpose: null means the author did not say,
 * which is not the same as `false`.
 */
public data class ChatlyCardVariant(
    val label: String,
    val value: String,
    val inStock: Boolean? = null,
)

/**
 * A product, offer or article card.
 *
 * [stock] is `in_stock`, `low` or `out_of_stock` when present. [reaction] is
 * what the AUTHOR says is chosen, and the reader drops it unless
 * [reactions] offers it — a chosen thumb with no control behind it is
 * something the visitor cannot un-press.
 */
public data class ChatlyCard(
    val title: String,
    val id: String? = null,
    val subtitle: String? = null,
    val image: String? = null,
    val badges: List<ChatlyCardBadge> = emptyList(),
    val price: ChatlyMoney? = null,
    val fields: List<ChatlyField> = emptyList(),
    val variants: List<ChatlyCardVariant> = emptyList(),
    val stock: String? = null,
    val actions: List<ChatlyBlockAction> = emptyList(),
    val reactions: List<String> = emptyList(),
    val reaction: String? = null,
    /** Fired by tapping the card's image or title. */
    val action: ChatlyBlockAction? = null,
)

/**
 * The trailing "show me more" tile on a grid, so a grid at its cap is not a
 * dead end. Its action is narrowed by the reader to the kinds that can page.
 */
public data class ChatlyGridMore(val label: String, val action: ChatlyBlockAction)

/**
 * One thing being compared, across the top of a table.
 *
 * A blank [label] means "the author sent something we could not read", not
 * "the author left it out" — the reader coerces rather than drops, because
 * dropping a column shifts every row's cells under the wrong heading.
 */
public data class ChatlyTableColumn(val label: String, val image: String? = null)

/** One attribute, down the side of a table. [cells] may be SHORTER than the columns. */
public data class ChatlyTableRow(val label: String, val cells: List<String>)

/**
 * One milestone on an order-status timeline. [state] is `done`, `current` or
 * `pending`; [at] is absent on a step that has not happened.
 */
public data class ChatlyTimelineStep(
    val label: String,
    val state: String,
    val at: String? = null,
)

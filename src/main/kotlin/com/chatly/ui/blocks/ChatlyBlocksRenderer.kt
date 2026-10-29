package com.chatly.ui.blocks

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.chatly.blocks.ChatlyBlock
import com.chatly.blocks.ChatlyBlockAction
import com.chatly.blocks.ChatlyBlockText
import com.chatly.blocks.ChatlyCard
import com.chatly.blocks.ChatlyField
import com.chatly.ui.ChatlyThumbnails
import com.chatly.ui.theme.ChatlyTheme

/**
 * Rich blocks inside a chat bubble: cards, product grids, fulfilment
 * timelines, comparison tables, quick replies.
 *
 * Mirrors `packages/sdk-react-native/src/components/MessageBlocks.tsx`, which
 * is the reference renderer. Everything a block SAYS is in
 * [ChatlyBlockText], which has no Android import and is therefore covered by
 * the JUnit suite; this file is the View layer and is compile-checked only by
 * the Gradle build.
 *
 * ── What a press does, and why it mostly leaves ────────────────────────
 *
 * The reference SDK answers a `postback` by calling `rest.postback`, a `step`
 * by `rest.requestCanvasStep`, and hands a `host_event` to the app. This SDK
 * has NONE of those three transports — [com.chatly.Chatly] exposes no
 * postback, no canvas step and no action report — so inventing a request
 * shape here would be a wire contract no server reads. A postback is
 * deliberately NOT mapped onto `send` either: a `silent` postback would
 * arrive as a visible visitor message, which is a different thing happening
 * on the shopper's own transcript.
 *
 * So every pressed action goes to [onAction], with the message id, and the
 * integrator runs their own payment sheet or screen from it — the
 * `host_event` posture of the reference, widened to every kind for lack of
 * transport. Two exceptions:
 *
 *  - a `url` action is ALSO opened natively (an `ACTION_VIEW` intent) when
 *    [onAction] is absent or returns false, because its href has already
 *    passed the `https`/`mailto`/`tel` allow-list in the reader and a link
 *    that does nothing is worse than one that opens;
 *  - a quick reply IS an ordinary visitor message (the reference sends it
 *    with `rest.reply`), so it goes to [onQuickReply], which
 *    [com.chatly.ui.ChatlyFragment] wires to `Chatly.send`.
 *
 * A `copy` action reaches [onAction] and nothing else: a clipboard write is
 * one line, but doing it silently under a label the author wrote would be
 * this package guessing. The reaction pair is drawn and inert for the same
 * transport reason — the reference sends a silent postback, which does not
 * exist here.
 *
 * ── A carousel scrolls, a grid stacks ──────────────────────────────────
 *
 * Never two columns: two cards side by side on a phone are narrower than the
 * images they carry. A row of buttons is a [HorizontalScrollView] rather
 * than a wrapping flex row, because Android has no wrapping row without a
 * dependency and a clipped button is unreachable.
 */
public class ChatlyBlocksRenderer(
    /** Palette. Set it from `Chatly.shared().theme` at bind time. */
    public var theme: ChatlyTheme = ChatlyTheme(),
    /**
     * Called for every pressed action with the action and the id of the
     * message it is on. Return `true` when the host handled it; a `url`
     * action falls back to an `ACTION_VIEW` intent otherwise.
     */
    public var onAction: ((ChatlyBlockAction, String) -> Boolean)? = null,
    /** Called with a quick reply's `value`, which is sent as a visitor message. */
    public var onQuickReply: ((String) -> Unit)? = null,
) {

    /**
     * Draw [blocks] into [container], replacing whatever was there.
     *
     * The clear is not optional: a RecyclerView reuses holders, so a
     * container left as it was shows the previous message's card.
     */
    public fun render(container: LinearLayout, blocks: List<ChatlyBlock>, messageId: String) {
        container.removeAllViews()
        if (blocks.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        container.orientation = LinearLayout.VERTICAL
        for (block in blocks) {
            val view = viewFor(container.context, block, messageId)
            // Only when the block did not choose its own: a divider's whole
            // appearance is its 1dp height, and overwriting that makes it a
            // zero-height nothing.
            if (view.layoutParams == null) {
                view.layoutParams = stackParams(container.context)
            }
            container.addView(view)
        }
    }

    private fun viewFor(ctx: Context, block: ChatlyBlock, messageId: String): View =
        when (block) {
            /*
             * The markdown is rendered as PLAIN TEXT, deliberately. A
             * markdown renderer is a dependency, and the characters a bot
             * emits — `**bold**`, a bullet, a link — read acceptably raw. A
             * consumer who wants more reads the envelope themselves.
             */
            is ChatlyBlock.Text -> body(ctx, block.markdown)
            is ChatlyBlock.Media -> mediaView(ctx, block, messageId)
            is ChatlyBlock.Fields -> fieldsView(ctx, block.items)
            is ChatlyBlock.Card -> cardView(ctx, block.card, messageId)
            is ChatlyBlock.CardGrid -> gridView(ctx, block, messageId)
            is ChatlyBlock.Actions -> actionsView(ctx, block.actions, messageId)
            is ChatlyBlock.QuickReplies -> quickRepliesView(ctx, block.options)
            is ChatlyBlock.OrderStatus -> timelineView(ctx, block)
            is ChatlyBlock.Table -> tableView(ctx, block)
            is ChatlyBlock.Place -> placeView(ctx, block)
            is ChatlyBlock.Status -> muted(ctx, ChatlyBlockText.statusLine(block))
            is ChatlyBlock.Divider -> dividerView(ctx)
        }

    private fun mediaView(ctx: Context, block: ChatlyBlock.Media, messageId: String): View {
        if (block.kind == "image") return imageView(ctx, block.url, block.alt)
        // No video player without a dependency. The alt text plus a tappable
        // link is strictly better than a blank space.
        val link = body(ctx, block.alt)
        link.setTextColor(theme.primary)
        link.setOnClickListener {
            dispatch(ctx, ChatlyBlockAction("url", block.alt, block.url), messageId)
        }
        return link
    }

    private fun imageView(ctx: Context, url: String, description: String): View {
        val image = ImageView(ctx)
        image.adjustViewBounds = true
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.contentDescription = description
        image.minimumHeight = dp(ctx, 120f)
        ChatlyThumbnails.load(image, url)
        return image
    }

    private fun fieldsView(ctx: Context, items: List<ChatlyField>): View {
        val column = stack(ctx)
        for (field in items) {
            column.addView(muted(ctx, ChatlyBlockText.fieldLine(field)))
        }
        return column
    }

    private fun cardView(ctx: Context, card: ChatlyCard, messageId: String): View {
        val column = stack(ctx)
        column.setPadding(dp(ctx, 10f), dp(ctx, 8f), dp(ctx, 10f), dp(ctx, 8f))
        column.setBackgroundColor(theme.border)

        val title = body(ctx, card.title)
        title.setTypeface(title.typeface, Typeface.BOLD)
        column.addView(title)
        card.subtitle?.let { column.addView(muted(ctx, it)) }

        if (card.badges.isNotEmpty()) {
            val badges = scrollRow(ctx)
            for (badge in card.badges) {
                val pill = muted(ctx, ChatlyBlockText.badgeText(badge))
                pill.contentDescription = ChatlyBlockText.badgeDescription(badge)
                pill.setPadding(dp(ctx, 6f), dp(ctx, 1f), dp(ctx, 6f), dp(ctx, 1f))
                if (badge.tone == "danger") pill.setTextColor(theme.primary)
                badges.row.addView(pill)
            }
            column.addView(badges.view)
        }

        val image = card.image
        if (image != null) column.addView(imageView(ctx, image, card.title))

        card.price?.let { price ->
            for (line in ChatlyBlockText.priceLines(price)) column.addView(body(ctx, line))
        }
        ChatlyBlockText.stockLabel(card.stock)?.let { column.addView(muted(ctx, it)) }

        for (variant in card.variants) {
            val row = muted(ctx, variant.label)
            // Absent `inStock` means the author did not say, which is not the
            // same as false — only a real false is dimmed.
            if (variant.inStock == false) row.alpha = 0.5f
            column.addView(row)
        }
        for (field in card.fields) column.addView(muted(ctx, ChatlyBlockText.fieldLine(field)))

        if (card.actions.isNotEmpty()) {
            column.addView(actionsView(ctx, card.actions, messageId))
        }
        if (card.reactions.isNotEmpty()) {
            column.addView(reactionsView(ctx, card))
        }

        /*
         * The card's own tap target hangs off the TITLE, not the card: a
         * clickable parent swallows the buttons inside it.
         */
        val tap = card.action
        if (tap != null) {
            title.setTextColor(theme.primary)
            title.setOnClickListener { dispatch(ctx, tap, messageId) }
        }
        return column
    }

    /**
     * The like/dislike pair, drawn and INERT.
     *
     * The reference answers a press with a silent postback, and this SDK has
     * no postback transport (see the header). Drawing them anyway is the
     * right half to keep: `card.reaction` is what the BOT says is chosen, so
     * a returning shopper sees the verdict their recommendations are being
     * ranked on rather than an un-pressed thumb.
     */
    private fun reactionsView(ctx: Context, card: ChatlyCard): View {
        val row = horizontalRow(ctx)
        for (reaction in card.reactions) {
            val label = if (reaction == "like") "Helpful" else "Not helpful"
            val chosen = card.reaction == reaction
            val view = muted(ctx, label)
            view.setPadding(0, dp(ctx, 4f), dp(ctx, 10f), 0)
            if (chosen) {
                view.setTypeface(view.typeface, Typeface.BOLD)
                view.setTextColor(theme.fg)
                view.contentDescription = "$label (selected)"
            }
            row.addView(view)
        }
        return row
    }

    private fun gridView(ctx: Context, block: ChatlyBlock.CardGrid, messageId: String): View {
        if (!block.carousel) {
            val column = stack(ctx)
            for (card in block.cards) column.addView(spaced(ctx, cardView(ctx, card, messageId)))
            block.more?.let {
                column.addView(actionButton(ctx, it.action.copy(label = it.label), messageId))
            }
            return column
        }
        val scroll = scrollRow(ctx)
        for (card in block.cards) {
            val view = cardView(ctx, card, messageId)
            view.layoutParams = LinearLayout.LayoutParams(dp(ctx, 240f), LinearLayout.LayoutParams.WRAP_CONTENT)
            scroll.row.addView(view)
        }
        /*
         * The TILE's label is what renders, not the action's: `more` carries
         * both, and the tile is the thing the shopper reads. Only the label
         * is overridden, so the postback's value and the url's href reach
         * [onAction] exactly as authored.
         */
        block.more?.let {
            scroll.row.addView(actionButton(ctx, it.action.copy(label = it.label), messageId))
        }
        return scroll.view
    }

    private fun actionsView(ctx: Context, actions: List<ChatlyBlockAction>, messageId: String): View {
        val scroll = scrollRow(ctx)
        for (action in actions) scroll.row.addView(actionButton(ctx, action, messageId))
        return scroll.view
    }

    private fun actionButton(ctx: Context, action: ChatlyBlockAction, messageId: String): View {
        val button = Button(ctx)
        button.text = action.label
        button.isAllCaps = false
        button.setOnClickListener { dispatch(ctx, action, messageId) }
        return button
    }

    private fun quickRepliesView(ctx: Context, options: List<ChatlyField>): View {
        val scroll = scrollRow(ctx)
        scroll.view.contentDescription = "Quick replies"
        for (option in options) {
            val chip = Button(ctx)
            chip.text = option.label
            chip.isAllCaps = false
            chip.setOnClickListener { onQuickReply?.invoke(option.value) }
            scroll.row.addView(chip)
        }
        return scroll.view
    }

    private fun timelineView(ctx: Context, block: ChatlyBlock.OrderStatus): View {
        val column = stack(ctx)
        column.contentDescription = "Order status"
        for (step in block.steps) {
            // The state is written out in words beside the label: a coloured
            // dot alone says nothing to a screen reader, and there is no dot.
            val line = ChatlyBlockText.stepLine(step)
            column.addView(if (step.state == "pending") muted(ctx, line) else body(ctx, line))
        }
        return column
    }

    private fun tableView(ctx: Context, block: ChatlyBlock.Table): View {
        val column = stack(ctx)
        block.caption?.let { column.addView(muted(ctx, it)) }
        val header = body(ctx, ChatlyBlockText.tableHeaderLine(block.columns))
        header.setTypeface(header.typeface, Typeface.BOLD)
        column.addView(header)
        for (row in block.rows) {
            column.addView(body(ctx, ChatlyBlockText.tableRowLine(row, block.columns.size)))
        }
        return column
    }

    private fun placeView(ctx: Context, block: ChatlyBlock.Place): View {
        val line = ChatlyBlockText.placeLine(block)
        val view = body(ctx, line)
        // "Place:" in the spoken name so a screen reader says what kind of
        // line it is. There is no button: a world host owns "take me there".
        view.contentDescription = "Place: $line"
        return view
    }

    private fun dividerView(ctx: Context): View {
        val line = View(ctx)
        line.setBackgroundColor(theme.border)
        line.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(ctx, 1f),
        )
        line.contentDescription = "divider"
        return line
    }

    /** One press: the host first, then the only fallback this SDK can make. */
    private fun dispatch(ctx: Context, action: ChatlyBlockAction, messageId: String) {
        val handled = onAction?.invoke(action, messageId) == true
        if (handled || action.kind != "url") return
        // The href passed the reader's https/mailto/tel allow-list.
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.value))) }
    }

    private fun body(ctx: Context, text: String): TextView {
        val view = TextView(ctx)
        view.text = text
        view.textSize = 15f
        view.setTextColor(theme.fg)
        return view
    }

    private fun muted(ctx: Context, text: String): TextView {
        val view = TextView(ctx)
        view.text = text
        view.textSize = 12f
        view.setTextColor(theme.mutedFg)
        return view
    }

    private fun stack(ctx: Context): LinearLayout {
        val column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        return column
    }

    private fun horizontalRow(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        return row
    }

    /** A horizontally scrolling row, and the row to put children in. */
    private fun scrollRow(ctx: Context): ScrollRow {
        val scroll = HorizontalScrollView(ctx)
        scroll.isHorizontalScrollBarEnabled = false
        val row = horizontalRow(ctx)
        scroll.addView(row)
        return ScrollRow(scroll, row)
    }

    private fun spaced(ctx: Context, view: View): View {
        val holder = stack(ctx)
        holder.setPadding(0, 0, 0, dp(ctx, 6f))
        holder.addView(view)
        return holder
    }

    private fun stackParams(ctx: Context): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dp(ctx, 4f)
        return params
    }

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    /** A [HorizontalScrollView] and its single child row. */
    private class ScrollRow(val view: HorizontalScrollView, val row: LinearLayout)
}

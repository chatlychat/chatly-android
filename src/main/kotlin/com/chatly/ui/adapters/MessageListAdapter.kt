package com.chatly.ui.adapters

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.chatly.R
import com.chatly.blocks.ChatlyBlocks
import com.chatly.models.Attachment
import com.chatly.models.MessageRow
import com.chatly.ui.ChatlyThumbnails
import com.chatly.ui.blocks.ChatlyBlocksRenderer

/**
 * RecyclerView adapter rendering visitor messages on the right and
 * agent/bot/system messages on the left. Per-message attachments are appended
 * inside the bubble — images as inline thumbnails, anything else as a small
 * file row with name and size.
 *
 * A `rich` message's [MessageRow.blocks] are drawn above the attachments by
 * [blocks] — cards, product grids, fulfilment timelines. Set that renderer's
 * `theme`, `onAction` and `onQuickReply` before the first bind;
 * [com.chatly.ui.ChatlyFragment] does.
 */
public class MessageListAdapter(
    private var items: List<MessageRow>,
    /** The rich-block renderer, exposed so a host can set its callbacks. */
    public val blocks: ChatlyBlocksRenderer = ChatlyBlocksRenderer(),
) : RecyclerView.Adapter<MessageListAdapter.MessageViewHolder>() {

    /** Replace items in-place and notify of the change. */
    public fun submit(newItems: List<MessageRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    /**
     * Append a single row. No-op when [row]'s id is already present so
     * redelivered realtime events don't double-render. Returns `true` if the
     * row was actually added.
     */
    public fun append(row: MessageRow): Boolean {
        if (items.any { it.id == row.id }) return false
        items = items + row
        notifyItemInserted(items.size - 1)
        return true
    }

    /** Current items, exposed for read-only inspection (e.g. tests). */
    public fun snapshot(): List<MessageRow> = items

    override fun getItemViewType(position: Int): Int =
        if (items[position].authorType == "contact") VIEW_TYPE_VISITOR else VIEW_TYPE_AGENT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = if (viewType == VIEW_TYPE_VISITOR) {
            R.layout.chatly_item_message_visitor
        } else {
            R.layout.chatly_item_message_agent
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view, blocks)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    /** Holds the bubble TextView and a column each for blocks and attachments. */
    public class MessageViewHolder(
        itemView: View,
        private val blocks: ChatlyBlocksRenderer = ChatlyBlocksRenderer(),
    ) : RecyclerView.ViewHolder(itemView) {
        private val body: TextView = itemView.findViewById(R.id.message_body)
        private val blocksColumn: LinearLayout = itemView.findViewById(R.id.message_blocks)
        private val attachments: LinearLayout = itemView.findViewById(R.id.message_attachments)

        internal fun bind(row: MessageRow) {
            body.text = row.body
            body.visibility = if (row.body.isEmpty()) View.GONE else View.VISIBLE

            /*
             * Blocks before attachments, and `render` clears the container
             * itself — a RecyclerView reuses holders, so a row with no blocks
             * would otherwise keep showing the previous message's card.
             */
            blocks.render(blocksColumn, ChatlyBlocks.read(row.blocks), row.id)

            attachments.removeAllViews()
            if (row.attachments.isEmpty()) {
                attachments.visibility = View.GONE
            } else {
                attachments.visibility = View.VISIBLE
                for (a in row.attachments) {
                    attachments.addView(viewForAttachment(a))
                }
            }
        }

        private fun viewForAttachment(a: Attachment): View {
            val ctx = itemView.context
            if (a.isImage) {
                val image = ImageView(ctx).apply {
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(ctx, 180f),
                    )
                    lp.topMargin = dp(ctx, 4f)
                    layoutParams = lp
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = a.filename
                }
                ChatlyThumbnails.load(image, a.url)
                return image
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(ctx, 8f), dp(ctx, 6f), dp(ctx, 8f), dp(ctx, 6f))
            }
            val title = TextView(ctx).apply {
                text = a.filename
                textSize = 13f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val size = TextView(ctx).apply {
                text = Formatter.formatShortFileSize(ctx, a.sizeBytes)
                textSize = 11f
                setPadding(dp(ctx, 6f), 0, 0, 0)
            }
            row.addView(title)
            row.addView(size)
            return row
        }

        private fun dp(ctx: android.content.Context, value: Float): Int =
            (value * ctx.resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val VIEW_TYPE_VISITOR = 1
        const val VIEW_TYPE_AGENT = 2
    }
}

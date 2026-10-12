package com.chatly.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.chatly.Chatly
import com.chatly.R
import com.chatly.models.Attachment

/**
 * Bottom composer with a paperclip attachment button, single-line [EditText]
 * and a send [ImageButton].
 *
 * Set [onSend] to receive the trimmed user input plus any staged attachments;
 * the view auto-clears on send. Set [onAttachClick] to launch the host's
 * picker — keep that wiring outside this view so the SDK doesn't need a hard
 * dependency on Activity Result APIs.
 */
public class ChatlyComposerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * Invoked with the trimmed message body and the currently-staged
     * [Attachment]s when the user taps send.
     */
    public var onSend: ((String, List<Attachment>) -> Unit)? = null

    /**
     * Invoked when the user taps the paperclip. Hosts wire this to launch
     * `Intent.ACTION_GET_CONTENT` (or the equivalent ActivityResultContract)
     * and then call [addAttachment]/[setUploading] to drive the chip strip.
     */
    public var onAttachClick: (() -> Unit)? = null

    /**
     * Invoked when the user taps the (x) on a staged chip — host can cancel
     * any in-flight upload tied to that placeholder id.
     */
    public var onRemoveAttachment: ((Attachment) -> Unit)? = null

    private val input: EditText
    private val sendButton: ImageButton
    private val attachButton: ImageButton
    private val chipScroll: HorizontalScrollView
    private val chipRow: LinearLayout
    private val staged: MutableList<Attachment> = mutableListOf()
    private var uploadCount: Int = 0
    private var uploadingChip: View? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.chatly_composer, this, true)
        input = findViewById(R.id.composer_input)
        sendButton = findViewById(R.id.composer_send)
        attachButton = findViewById(R.id.composer_attach)
        chipScroll = findViewById(R.id.composer_chip_scroll)
        chipRow = findViewById(R.id.composer_chips)
        val theme = runCatching { Chatly.shared().theme }.getOrNull()
        if (theme != null) {
            setBackgroundColor(theme.bg)
            input.setTextColor(theme.fg)
            input.setHintTextColor(theme.mutedFg)
            sendButton.setColorFilter(theme.primary)
            attachButton.setColorFilter(theme.primary)
        }
        attachButton.setOnClickListener { onAttachClick?.invoke() }
        sendButton.setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            val attachments = staged.toList()
            if (text.isEmpty() && attachments.isEmpty()) return@setOnClickListener
            onSend?.invoke(text, attachments)
            input.text?.clear()
            staged.clear()
            chipRow.removeAllViews()
            chipScroll.visibility = View.GONE
        }
    }

    /**
     * Shows an in-progress chip while [setUploading] is true. Useful when the
     * host kicks off an upload but doesn't yet have the resolved [Attachment].
     */
    public fun setUploading(uploading: Boolean) {
        if (uploading) {
            if (uploadCount == 0) {
                uploadingChip = addSpinnerChip()
            }
            uploadCount += 1
        } else {
            uploadCount = (uploadCount - 1).coerceAtLeast(0)
            if (uploadCount == 0) {
                uploadingChip?.let { chipRow.removeView(it) }
                uploadingChip = null
                if (chipRow.childCount == 0) {
                    chipScroll.visibility = View.GONE
                }
            }
        }
    }

    /** Adds a staged attachment chip — call after an upload resolves. */
    public fun addAttachment(attachment: Attachment) {
        staged.add(attachment)
        addChip(attachment)
        chipScroll.visibility = View.VISIBLE
    }

    /** Snapshot of currently-staged attachments. */
    public fun stagedAttachments(): List<Attachment> = staged.toList()

    private fun addChip(attachment: Attachment): View {
        val theme = runCatching { Chatly.shared().theme }.getOrNull()
        val chip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setBackgroundResource(R.drawable.chatly_bubble_agent)
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            layoutParams = lp
        }
        val label = TextView(context).apply {
            text = attachment.filename
            textSize = 12f
            maxWidth = dp(160)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            if (theme != null) setTextColor(theme.fg)
        }
        chip.addView(label)
        val remove = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            contentDescription = context.getString(R.string.chatly_attachment_remove)
            if (theme != null) setColorFilter(theme.mutedFg)
            layoutParams = LayoutParams(dp(28), dp(28)).also { it.marginStart = dp(4) }
            setOnClickListener {
                staged.removeAll { it.id == attachment.id }
                chipRow.removeView(chip)
                if (chipRow.childCount == 0) {
                    chipScroll.visibility = View.GONE
                }
                onRemoveAttachment?.invoke(attachment)
            }
        }
        chip.addView(remove)
        chipRow.addView(chip)
        return chip
    }

    private fun addSpinnerChip(): View {
        val theme = runCatching { Chatly.shared().theme }.getOrNull()
        val chip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundResource(R.drawable.chatly_bubble_agent)
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            layoutParams = lp
        }
        val label = TextView(context).apply {
            text = "Uploading…"
            textSize = 12f
            if (theme != null) setTextColor(theme.fg)
        }
        chip.addView(label)
        val spinner = ProgressBar(context).apply {
            layoutParams = LayoutParams(dp(16), dp(16)).also { it.marginStart = dp(6) }
            isIndeterminate = true
        }
        chip.addView(spinner)
        chipRow.addView(chip)
        chipScroll.visibility = View.VISIBLE
        return chip
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

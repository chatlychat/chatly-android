package com.chatly.models

import kotlinx.serialization.Serializable

/**
 * One file or image attached to a message.
 *
 * Returned by `POST /v1/widget/uploads` and echoed back on the message
 * payload. Image attachments (mime types starting with `image/`) render as
 * inline thumbnails; everything else renders as a file chip.
 */
@Serializable
public data class Attachment(
    val id: String,
    val url: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    /** True when this attachment should be rendered as an inline image. */
    val isImage: Boolean
        get() = mimeType.lowercase().startsWith("image/")
}

/**
 * Message row as returned by `/v1/widget/messages/list`.
 *
 * @property authorType `contact` | `agent` | `bot` | `system`.
 * @property kind `text` | `image` | `file` | `event` | `attachment_only` | `internal_note`.
 */
@Serializable
public data class MessageRow(
    val id: String,
    val authorType: String,
    val kind: String,
    val body: String,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: String,
)

/** Wrapper for `/v1/widget/messages/list`. */
@Serializable
public data class MessageListResponse(
    val messages: List<MessageRow> = emptyList(),
)

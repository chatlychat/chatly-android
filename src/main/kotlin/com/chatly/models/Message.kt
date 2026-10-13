package com.chatly.models

import kotlinx.serialization.Serializable

/**
 * One file or image attached to a message.
 *
 * Returned by `POST /v1/widget/uploads` and echoed back on the message
 * payload. Image attachments (mime types starting with `image/`) render as
 * inline thumbnails; everything else renders as a file chip.
 */
/*
 * Everything but `id` carries a default.
 *
 * `messages.attachments` is a jsonb column and the message DTO serves it
 * through unchanged, so a row written before a field existed — or by an
 * importer, or by the API-key send path — can be missing any of these. A
 * strict decode turns that into a `MissingFieldException` on an otherwise
 * fine reply, which reads as a broken SDK rather than a thin attachment.
 */
@Serializable
public data class Attachment(
    val id: String,
    val url: String = "",
    val filename: String = "",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
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

package com.chatly.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
 * @property kind `text` | `rich` | `attachment_only` | `system_event`.
 *
 * This line used to read
 * `text | image | file | event | attachment_only | internal_note`, and four
 * of those six are not members of the server's `message_kind` enum
 * (`text | rich | note | system_event | attachment_only`) — so a reader
 * writing `kind == "image"` against the documented contract was writing a
 * comparison that is always false. `rich`, the kind every formatted reply and
 * every bot card carries, was not documented at all.
 *
 * `note` is absent on purpose: an agent's internal comment never reaches a
 * visitor. The realtime gateway's `VISITOR_VISIBLE_MESSAGE_KINDS` excludes
 * it; the history route's SQL excludes it and `system_event` as well, so
 * `system_event` arrives on the socket and is gone after a reload.
 */
@Serializable
public data class MessageRow(
    val id: String,
    val authorType: String,
    val kind: String,
    val body: String,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: String,
    /**
     * The rich-block envelope, `{ v: 1, blocks: [...] }`, on a `rich`
     * message — a card, a product grid, a fulfilment timeline, a comparison
     * table. Null on a plain one.
     *
     * Loosely typed ON PURPOSE, matching `MessageRow.blocks` in
     * `packages/sdk-react-native`. `kind = 'rich'` is the most common
     * non-text kind on a visitor thread (every bot card is one), and until
     * 2026-10-27 this field did not exist at all, so every card on Android
     * arrived as bare `body` text with no error. A typed envelope here would
     * trade that silence for a different failure: a server newer than the
     * app sends block types this app has never heard of, and a strict decode
     * would throw away the whole MESSAGE rather than the one block it cannot
     * draw. `JsonElement` accepts any JSON value, so a malformed `blocks`
     * cannot fail the decode of the message it rides on.
     *
     * Read it with [com.chatly.blocks.ChatlyBlocks.read], which validates at
     * render time and drops what it cannot draw. The twelve block types the
     * SDK renders and the two spatial fields it does not are documented on
     * [com.chatly.blocks.ChatlyBlock].
     */
    val blocks: JsonElement? = null,
)

/** Wrapper for `/v1/widget/messages/list`. */
@Serializable
public data class MessageListResponse(
    val messages: List<MessageRow> = emptyList(),
)

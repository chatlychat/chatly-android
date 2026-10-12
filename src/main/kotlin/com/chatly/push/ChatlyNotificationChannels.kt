package com.chatly.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput as CoreRemoteInput

/**
 * Rich-push channel + action setup for the Chatly Android SDK.
 *
 * Mirrors `apps/worker/src/handlers/push.ts` and the Swift counterpart in
 * `packages/sdk-swift/Sources/Chatly/Push/PushTokenRegistrar.swift`. The
 * backend sets `android.notification.click_action = "CHATLY_MESSAGE"` and the
 * SDK uses that as the channel id so the FCM-rendered notification picks up
 * the channel's importance + the action buttons configured below.
 *
 * Host apps invoke [registerNotificationChannel] once on application start
 * (e.g. from `Application.onCreate`). It is idempotent — Android's channel
 * APIs are upsert-style.
 */
object ChatlyNotificationChannels {
    /** Notification channel id. Must match `aps.category` on iOS. */
    const val CHATLY_MESSAGE_CHANNEL = "CHATLY_MESSAGE"

    /** Action ids — host receivers should branch on these. */
    const val REPLY_ACTION = "com.chatly.action.REPLY"
    const val MARK_READ_ACTION = "com.chatly.action.MARK_READ"
    const val VIEW_ACTION = "com.chatly.action.VIEW"

    /** RemoteInput key for the inline reply text. */
    const val KEY_TEXT_REPLY = "key_text_reply"

    /**
     * Register the Chatly notification channel. No-op on API < 26 where
     * channels are not a concept.
     *
     * The channel itself is what carries the action buttons on Android —
     * actions are attached per-notification, not per-channel, so we expose
     * [buildActions] for the FCM service to call when constructing a
     * NotificationCompat.Builder.
     */
    fun registerNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHATLY_MESSAGE_CHANNEL,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "New messages and replies from Chatly conversations."
            enableLights(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the action buttons attached to a Chatly message notification.
     * Caller is responsible for wiring [replyIntent] / [markReadIntent] /
     * [viewIntent] to broadcast receivers or activities that will round-trip
     * back to the API.
     *
     * The reply action uses [RemoteInput] so the user can type directly from
     * the notification shade without unlocking the device.
     */
    fun buildActions(
        replyIntent: PendingIntent,
        markReadIntent: PendingIntent,
        viewIntent: PendingIntent,
    ): List<NotificationCompat.Action> {
        val remoteInput = CoreRemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Type your reply...")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
        val markReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Mark as read",
            markReadIntent,
        ).build()
        val viewAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "View",
            viewIntent,
        ).build()
        return listOf(replyAction, markReadAction, viewAction)
    }

    /**
     * Extract the inline reply text from a [RemoteInput]-backed intent.
     * Returns null when the intent doesn't carry a reply (e.g. the user
     * tapped a different action).
     */
    fun extractReplyText(intent: Intent): String? {
        val bundle = RemoteInput.getResultsFromIntent(intent) ?: return null
        val text = bundle.getCharSequence(KEY_TEXT_REPLY) ?: return null
        val trimmed = text.toString().trim()
        return trimmed.ifEmpty { null }
    }
}

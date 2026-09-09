package dev.recally.fcm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.recally.MainActivity
import dev.recally.R
import dev.recally.ui.navigation.EXTRA_DEEP_LINK_ROUTE
import dev.recally.ui.navigation.Screen

/**
 * Builds the due-cards notification from the server's **data** payload
 * (docs/android.md, "Push notifications"). The payload carries `due_count`
 * and `book_title` only; the app renders the notification itself — a
 * `notification` payload would be rendered by the system while the app is
 * backgrounded and would ignore the channel and the deep link.
 */
object DueCardsNotification {
    const val CHANNEL_ID = "due_cards"
    const val NOTIFICATION_ID = 1

    private const val KEY_DUE_COUNT = "due_count"
    private const val KEY_BOOK_TITLE = "book_title"

    /** The copy: "12 cards due from Evals for AI Engineers". */
    fun formatBody(
        dueCount: Int,
        bookTitle: String,
    ): String {
        val noun = if (dueCount == 1) "card" else "cards"
        return "$dueCount $noun due from $bookTitle"
    }

    /**
     * The intent posted with the notification. It opens MainActivity with the
     * Today route as its only payload — no card ids, because anything the
     * notification carried would be stale by the time it is tapped; Today
     * refetches (docs/android.md, "Push notifications").
     */
    fun tapIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_DEEP_LINK_ROUTE, Screen.Today.route)

    /** Builds the notification, or null when the payload is not a due-cards message. */
    fun build(
        context: Context,
        data: Map<String, String>,
    ): Notification? {
        val dueCount = data[KEY_DUE_COUNT]?.toIntOrNull() ?: return null
        val bookTitle = data[KEY_BOOK_TITLE]?.takeIf { it.isNotBlank() } ?: return null
        val tap =
            PendingIntent.getActivity(
                context,
                0,
                tapIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentText(formatBody(dueCount, bookTitle))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
    }

    /** Creates the channel — idempotent; call before posting. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_due_cards),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }
}

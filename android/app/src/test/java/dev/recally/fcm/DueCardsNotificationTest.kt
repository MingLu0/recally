package dev.recally.fcm

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.recally.ui.navigation.EXTRA_DEEP_LINK_ROUTE
import dev.recally.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Notification-building gate for roadmap step 5c (issue #91). The server
 * sends a **data** message only (5b asserts no `notification` payload), so
 * the app renders the notification itself — on the app's channel, with the
 * app's deep link (docs/android.md, "Push notifications").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DueCardsNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The data-only payload contract: count + book name, nothing else. */
    private val dataPayload =
        mapOf(
            "due_count" to "12",
            "book_title" to "Evals for AI Engineers",
        )

    @Test
    fun test_data_message_builds_a_notification_with_the_payload_count() {
        // No `notification` payload is involved: the data map alone must
        // produce the rendered notification.
        val notification = DueCardsNotification.build(context, dataPayload)

        assertNotNull("a data message with a count and book name builds a notification", notification)
        val text = notification!!.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertNotNull(text)
        assertTrue("the count from the payload is named: $text", text!!.contains("12"))
        assertTrue("the book from the payload is named: $text", text.contains("Evals for AI Engineers"))
    }

    @Test
    fun test_notification_carries_no_card_ids() {
        val intent = DueCardsNotification.tapIntent(context)

        // The tap opens Today, which refetches — anything the notification
        // carried would be stale by the time it is tapped.
        assertEquals(
            "the tap intent targets the Today route",
            Screen.Today.route,
            intent.getStringExtra(EXTRA_DEEP_LINK_ROUTE),
        )
        val keys = intent.extras?.keySet().orEmpty()
        assertTrue(
            "the tap intent carries no card list (Today refetches): $keys",
            keys.none { it.contains("card", ignoreCase = true) },
        )
    }
}

package dev.recally.fcm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import dev.recally.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles FCM **data** messages (docs/android.md, "Push notifications"): the
 * server sends no `notification` payload, so this service renders the
 * notification itself via [DueCardsNotification] — on the app's channel, with
 * the deep link to Today.
 *
 * Data messages never arrive after a force-stop, and some OEM battery
 * managers drop them — nothing in the app depends on a push having arrived.
 */
@AndroidEntryPoint
class RecallyFirebaseMessagingService : FirebaseMessagingService() {
    @Inject
    lateinit var deviceRegistration: FcmDeviceRegistration

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = DueCardsNotification.build(this, message.data) ?: return
        // POST_NOTIFICATIONS is a runtime permission from API 33; a denial
        // means no notification is posted, and Today works regardless.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        DueCardsNotification.ensureChannel(this)
        NotificationManagerCompat.from(this).notify(DueCardsNotification.NOTIFICATION_ID, notification)
    }

    override fun onNewToken(token: String) {
        serviceScope.launch(CoroutineExceptionHandler { _, _ -> }) {
            deviceRegistration.onTokenRefreshed(token)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

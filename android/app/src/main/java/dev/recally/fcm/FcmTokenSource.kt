package dev.recally.fcm

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the current FCM registration token comes from. An interface so tests
 * never touch Firebase (docs/android.md, "Tests").
 */
interface FcmTokenSource {
    /** The current token, or null when Firebase is not initialised in this build. */
    suspend fun currentToken(): String?
}

/**
 * Firebase-backed [FcmTokenSource]. A build without `google-services.json`
 * (gitignored; the google-services Gradle plugin is skipped then) never
 * initialises Firebase, so there is no token and registration simply does
 * not happen.
 */
@Singleton
class FirebaseFcmTokenSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : FcmTokenSource {
        override suspend fun currentToken(): String? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseMessaging.getInstance().token.await()
        }
    }

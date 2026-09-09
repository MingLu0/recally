package dev.recally.fcm

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.recally.data.remote.RecallyApiFactory
import dev.recally.data.settings.DeviceRegistrar
import dev.recally.data.settings.SettingsCipher
import dev.recally.data.settings.SettingsStore
import dev.recally.domain.repository.DeviceRegistrationRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Device-registration gate for roadmap step 5c (issue #91): `POST /devices`
 * on app start and on every FCM token refresh (docs/android.md, "Push
 * notifications"), a no-op while Settings is unconfigured, and never fatal —
 * the Today screen must work when registration cannot happen.
 *
 * Tests 2 and 3 run the real stack (SettingsStore + DeviceRegistrar +
 * MockWebServer) so the assertion is literally "one POST /devices"; the
 * guard/failure tests use a counting fake at the repository-interface seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FcmDeviceRegistrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var storeScope: CoroutineScope
    private lateinit var store: SettingsStore

    /** Reversible stand-in for the Keystore cipher (no AndroidKeyStore on the JVM). */
    private class FakeSettingsCipher : SettingsCipher {
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()

        override fun decrypt(ciphertext: String): String = ciphertext.removePrefix("enc:").reversed()
    }

    /** Programmable token source — no Firebase on the JVM. */
    private class FakeFcmTokenSource(
        private val token: String? = "fcm-token-a",
        private val failure: IOException? = null,
    ) : FcmTokenSource {
        var calls = 0
            private set

        override suspend fun currentToken(): String? {
            calls++
            failure?.let { throw it }
            return token
        }
    }

    /** Counting stand-in at the repository seam the FCM side depends on. */
    private class FakeDeviceRegistration(
        private val result: Result<Long> = Result.Success(1L),
    ) : DeviceRegistrationRepository {
        val tokens = mutableListOf<String>()

        override suspend fun registerDevice(fcmToken: String): Result<Long> {
            tokens += fcmToken
            return result
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store =
            SettingsStore(
                dataStore =
                    PreferenceDataStoreFactory.create(
                        scope = storeScope,
                        produceFile = { temporaryFolder.newFile("recally_5c.preferences_pb") },
                    ),
                cipher = FakeSettingsCipher(),
                ioDispatcher = Dispatchers.IO,
            )
    }

    @After
    fun tearDown() =
        runTest {
            server.shutdown()
            storeScope.coroutineContext.job.cancelAndJoin()
        }

    private suspend fun configureStore() {
        store.saveConnection(server.url("/").toString().removeSuffix("/"), "test-api-key")
    }

    private fun enqueueDeviceId(deviceId: Long) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"device_id":$deviceId}"""),
        )
    }

    /** The real stack: FcmDeviceRegistration over DeviceRegistrar over MockWebServer. */
    private fun realRegistration(tokenSource: FcmTokenSource): FcmDeviceRegistration =
        FcmDeviceRegistration(
            settings = store,
            registration = DeviceRegistrar(RecallyApiFactory.create(store), store, Dispatchers.IO),
            tokenSource = tokenSource,
        )

    @Test
    fun test_token_is_registered_on_app_start() =
        runTest {
            configureStore()
            enqueueDeviceId(3L)

            realRegistration(FakeFcmTokenSource(token = "fcm-token-abc")).registerOnAppStart()

            assertEquals("exactly one POST /devices on app start", 1, server.requestCount)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/devices", recorded.path)
            val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals("fcm-token-abc", body.getValue("fcm_token").jsonPrimitive.content)
            assertEquals("android", body.getValue("platform").jsonPrimitive.content)
            assertEquals(3L, store.deviceId())
        }

    @Test
    fun test_device_id_is_overwritten_on_token_refresh() =
        runTest {
            configureStore()
            enqueueDeviceId(3L)
            enqueueDeviceId(7L)

            val registration = realRegistration(FakeFcmTokenSource(token = "fcm-token-a"))
            registration.registerOnAppStart()
            assertEquals(3L, store.deviceId())

            // A refreshed fcm_token is a new row and a new id (docs/android.md,
            // "Offline-first sync") — the stored value is replaced, never kept.
            registration.onTokenRefreshed("fcm-token-b")

            assertEquals(
                "the refreshed registration's device_id replaces the stored one",
                7L,
                store.deviceId(),
            )
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val refreshRequest = server.takeRequest()
            assertEquals("/devices", refreshRequest.path)
            val body = Json.parseToJsonElement(refreshRequest.body.readUtf8()).jsonObject
            assertEquals("fcm-token-b", body.getValue("fcm_token").jsonPrimitive.content)
        }

    @Test
    fun test_registration_is_skipped_when_settings_are_unconfigured() =
        runTest {
            // No saveConnection: no base URL, no API key — first launch.
            val registrationRepository = FakeDeviceRegistration()
            val registration =
                FcmDeviceRegistration(
                    settings = store,
                    registration = registrationRepository,
                    tokenSource = FakeFcmTokenSource(token = "fcm-token-abc"),
                )

            registration.registerOnAppStart()
            registration.onTokenRefreshed("fcm-token-abc")

            assertTrue(
                "no registration call while Settings is unconfigured",
                registrationRepository.tokens.isEmpty(),
            )
        }

    @Test
    fun test_registration_failure_is_not_fatal() =
        runTest {
            configureStore()

            // A 401 surfaces as Result.Unauthorized: contained, exactly one attempt.
            val registrationRepository = FakeDeviceRegistration(result = Result.Unauthorized)
            FcmDeviceRegistration(
                settings = store,
                registration = registrationRepository,
                tokenSource = FakeFcmTokenSource(),
            ).registerOnAppStart()
            assertEquals("no retry in a tight loop after a 401", 1, registrationRepository.tokens.size)

            // A connection failure fetching the token: also contained, no retry.
            val failingTokenSource = FakeFcmTokenSource(failure = IOException("connection failed"))
            FcmDeviceRegistration(
                settings = store,
                registration = registrationRepository,
                tokenSource = failingTokenSource,
            ).registerOnAppStart()
            assertEquals("no retry in a tight loop after a connection failure", 1, failingTokenSource.calls)
            assertEquals(1, registrationRepository.tokens.size)
        }
}

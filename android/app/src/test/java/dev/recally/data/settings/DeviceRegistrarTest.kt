package dev.recally.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.recally.data.remote.RecallyApiFactory
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

/**
 * Device-registration gate for roadmap step 4e (issue #56): `POST /devices`
 * runs on app start and on every FCM token refresh, and the stored
 * `device_id` is overwritten from every response — a refreshed `fcm_token` is
 * a new row and a new id (docs/android.md, "Offline-first sync").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRegistrarTest {
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

    @Before
    fun setUp() =
        runTest {
            server = MockWebServer()
            server.start()
            storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            store =
                SettingsStore(
                    dataStore =
                        PreferenceDataStoreFactory.create(
                            scope = storeScope,
                            produceFile = { temporaryFolder.newFile("recally_devices.preferences_pb") },
                        ),
                    cipher = FakeSettingsCipher(),
                    ioDispatcher = Dispatchers.IO,
                )
            store.saveConnection(server.url("/").toString().removeSuffix("/"), "test-api-key")
        }

    @After
    fun tearDown() =
        runTest {
            server.shutdown()
            storeScope.coroutineContext.job.cancelAndJoin()
        }

    private fun enqueueDeviceId(deviceId: Long) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"device_id":$deviceId}"""),
        )
    }

    @Test
    fun test_device_id_is_overwritten_on_every_registration() =
        runTest {
            enqueueDeviceId(3L)
            enqueueDeviceId(7L)

            val api = RecallyApiFactory.create(store)
            val registrar = DeviceRegistrar(api, store, Dispatchers.IO)

            val first = registrar.register("token-a")
            assertTrue(first is Result.Success)
            assertEquals(3L, (first as Result.Success).data)
            assertEquals(3L, store.deviceId())

            // A refreshed fcm_token is a new row and a new id: the stored
            // value is replaced, not kept (docs/android.md).
            val second = registrar.register("token-b")
            assertTrue(second is Result.Success)
            assertEquals(7L, (second as Result.Success).data)
            assertEquals(
                "the second response's device_id replaces the stored one",
                7L,
                store.deviceId(),
            )

            // Request shape per docs/api-spec.md, "Devices".
            assertEquals(2, server.requestCount)
            val tokens = mutableListOf<String>()
            repeat(2) {
                val recorded = server.takeRequest()
                assertEquals("POST", recorded.method)
                assertEquals("/devices", recorded.path)
                val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
                assertEquals("android", body.getValue("platform").jsonPrimitive.content)
                tokens += body.getValue("fcm_token").jsonPrimitive.content
            }
            assertEquals(listOf("token-a", "token-b"), tokens)
        }
}

package dev.recally.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Store gate for roadmap step 4e (issue #56): values written to the
 * Keystore-encrypted DataStore are readable from a fresh instance (a process
 * restart), and the file at rest never holds the plaintext key.
 *
 * The production cipher is backed by AndroidKeyStore, which does not exist on
 * the JVM; the store is tested against a fake cipher so the DataStore round
 * trip runs here (docs/android.md, "Tests").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Reversible stand-in for the Keystore cipher; output never contains the input. */
    private class FakeSettingsCipher : SettingsCipher {
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()

        override fun decrypt(ciphertext: String): String = ciphertext.removePrefix("enc:").reversed()
    }

    private fun newStore(
        file: File,
        scope: CoroutineScope,
    ): SettingsStore =
        SettingsStore(
            dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
            cipher = FakeSettingsCipher(),
            ioDispatcher = Dispatchers.IO,
        )

    @Test
    fun test_settings_survive_process_restart() =
        runTest {
            val storeFile = temporaryFolder.newFile("recally_settings.preferences_pb")

            // First "process": write everything the Settings screen owns.
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val firstStore = newStore(storeFile, firstScope)
            firstStore.saveConnection(BASE_URL, API_KEY)
            firstStore.saveDeviceId(3L)
            // Close the store before the fresh instance: DataStore refuses two
            // active instances of one file, and close is the process-death
            // analogue here.
            firstScope.coroutineContext.job.cancelAndJoin()

            // Second "process": a fresh DataStore over the same file.
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val secondStore = newStore(storeFile, secondScope)
            assertNull(
                "a fresh instance's synchronous cache starts empty until load()",
                secondStore.apiKey(),
            )

            val loaded = secondStore.load()
            assertEquals(BASE_URL, loaded.baseUrl)
            assertTrue(loaded.hasApiKey)
            assertEquals(3L, loaded.deviceId)

            // The synchronous interceptor face (ConnectionSettingsProvider) is
            // populated from the decrypted cache.
            assertEquals(API_KEY, secondStore.apiKey())
            assertEquals(BASE_URL, secondStore.baseUrl())

            // At rest the file holds ciphertext, never the plaintext key.
            val onDisk = String(storeFile.readBytes())
            assertFalse("the API key must not be stored in plain text", onDisk.contains(API_KEY))

            secondScope.coroutineContext.job.cancelAndJoin()
        }

    private companion object {
        const val BASE_URL = "http://192.168.1.42:8000"
        const val API_KEY = "super-secret-key"
    }
}

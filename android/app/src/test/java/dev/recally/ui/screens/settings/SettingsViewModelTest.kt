package dev.recally.ui.screens.settings

import dev.recally.domain.repository.ConnectionTester
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.SettingsRepository
import dev.recally.domain.repository.StoredConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.UnknownServiceException

/**
 * ViewModel gate for roadmap step 4e (issue #56). Fake repository and tester,
 * TestDispatcher for Main (docs/android.md, "Tests").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** In-memory SettingsRepository; records what a save would persist. */
    private class FakeSettingsRepository(
        private val storedBaseUrl: String = STORED_BASE_URL,
        private val storedHasApiKey: Boolean = true,
    ) : SettingsRepository {
        var savedBaseUrl: String? = null

        /** Null means the save kept the stored key; [UNSET] means never saved. */
        var savedApiKey: String? = UNSET

        override suspend fun load(): StoredConnection =
            StoredConnection(
                baseUrl = storedBaseUrl,
                hasApiKey = storedHasApiKey,
                deviceId = 3,
            )

        override suspend fun saveConnection(
            baseUrl: String,
            apiKey: String?,
        ) {
            savedBaseUrl = baseUrl
            savedApiKey = apiKey
        }

        override suspend fun saveDeviceId(deviceId: Long) = Unit
    }

    private class FakeConnectionTester(
        var result: Result<Unit>,
    ) : ConnectionTester {
        override suspend fun test(): Result<Unit> = result
    }

    private fun viewModelWith(
        testResult: Result<Unit>,
        repository: FakeSettingsRepository = FakeSettingsRepository(),
    ): SettingsViewModel =
        SettingsViewModel(
            settings = repository,
            connectionTester = FakeConnectionTester(testResult),
            appVersion = "0.1.0-test",
            ioDispatcher = testDispatcher,
        )

    @Test
    fun test_connection_test_maps_four_outcomes() =
        runTest {
            // 200 — URL and key both right.
            val connectedViewModel = viewModelWith(Result.Success(Unit))
            connectedViewModel.testConnection()
            advanceUntilIdle()
            val connected = connectedViewModel.uiState.value.connectionTest
            assertTrue("200 must map to Connected", connected is ConnectionTestState.Connected)

            // 401 — the key is wrong.
            val wrongKeyViewModel = viewModelWith(Result.Unauthorized)
            wrongKeyViewModel.testConnection()
            advanceUntilIdle()
            val wrongKey = wrongKeyViewModel.uiState.value.connectionTest
            assertTrue("401 must map to WrongKey", wrongKey is ConnectionTestState.WrongKey)

            // Connection failure — the server could not be reached at all.
            val unreachableViewModel =
                viewModelWith(
                    Result.NetworkError(IOException("failed to connect to /192.168.1.42:8000")),
                )
            unreachableViewModel.testConnection()
            advanceUntilIdle()
            val unreachable = unreachableViewModel.uiState.value.connectionTest
            assertTrue("a connection failure must map to Unreachable", unreachable is ConnectionTestState.Unreachable)

            // Cleartext blocked by the network security config before the
            // request left the phone (release build, docs/android.md).
            val httpsViewModel =
                viewModelWith(
                    Result.NetworkError(
                        UnknownServiceException(
                            "CLEARTEXT communication to example.com not permitted by network security policy",
                        ),
                    ),
                )
            httpsViewModel.testConnection()
            advanceUntilIdle()
            val httpsRequired = httpsViewModel.uiState.value.connectionTest
            assertTrue("a cleartext block must map to HttpsRequired", httpsRequired is ConnectionTestState.HttpsRequired)

            // Four outcomes, four distinct user-visible messages.
            val messages =
                listOf(
                    requireNotNull(connected.message),
                    requireNotNull(wrongKey.message),
                    requireNotNull(unreachable.message),
                    requireNotNull(httpsRequired.message),
                )
            assertEquals(
                "each of the four outcomes carries its own message",
                4,
                messages.toSet().size,
            )
        }

    @Test
    fun test_api_key_is_never_exposed_in_ui_state() =
        runTest {
            val repository = FakeSettingsRepository()
            val viewModel = viewModelWith(Result.Success(Unit), repository)
            advanceUntilIdle()

            val loaded = viewModel.uiState.value
            assertTrue(loaded.hasStoredApiKey)
            assertNotEquals(
                "the stored key must not come back as plain text for display",
                STORED_API_KEY,
                loaded.apiKey,
            )
            assertEquals(API_KEY_MASK, loaded.apiKey)
            for (field in SettingsUiState::class.java.declaredFields) {
                field.isAccessible = true
                assertNotEquals(
                    "SettingsUiState.${field.name} must never carry the stored key",
                    STORED_API_KEY,
                    field.get(loaded),
                )
            }

            // Testing the connection with the mask untouched must not persist
            // the sentinel as the key — null means "keep the stored key".
            viewModel.testConnection()
            advanceUntilIdle()
            assertNull("the mask sentinel is never saved as the key", repository.savedApiKey)
        }

    @Test
    fun test_push_window_row_is_read_only() {
        // The window is exposed as a plain display value (design-system.md,
        // "States": read-only status row, never a toggle).
        val state = SettingsUiState()
        assertEquals(PUSH_WINDOW_DISPLAY, state.pushWindow)

        val windowFields =
            SettingsUiState::class.java.declaredFields.filter {
                it.name.contains("pushWindow", ignoreCase = true)
            }
        assertEquals("the UiState carries the window as exactly one field", 1, windowFields.size)
        assertEquals("the window is a display value, not state to edit", String::class.java, windowFields[0].type)

        val viewModelMutators =
            SettingsViewModel::class.java.declaredMethods.filter {
                it.name.contains("pushWindow", ignoreCase = true)
            }
        assertTrue(
            "the ViewModel must not offer a push-window mutator",
            viewModelMutators.isEmpty(),
        )
    }

    private companion object {
        const val STORED_BASE_URL = "http://192.168.1.42:8000"
        const val STORED_API_KEY = "super-secret-key"
        const val UNSET = "__unset__"
    }
}

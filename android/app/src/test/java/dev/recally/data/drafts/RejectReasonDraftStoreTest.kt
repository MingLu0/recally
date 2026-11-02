package dev.recally.data.drafts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Store gate for issue #343: reject-reason drafts are kept per card, survive
 * a process restart, a blank save removes the draft, and a storage failure
 * answers nothing instead of throwing — a draft is a convenience, never a
 * blocker (docs/android.md, "Screens → 3").
 *
 * Follows [dev.recally.data.settings.SettingsStoreTest]: a real
 * PreferenceDataStoreFactory over a temp file, and a fresh store instance
 * over the same file standing in for the restarted process (DataStore
 * refuses two live instances of one file, so the first scope is cancelled
 * first — close is the process-death analogue).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RejectReasonDraftStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newStore(
        file: File,
        scope: CoroutineScope,
    ): RejectReasonDraftStore =
        RejectReasonDraftStore(
            dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
            ioDispatcher = Dispatchers.IO,
        )

    @Test
    fun test_drafts_survive_process_restart() =
        runTest {
            val storeFile = temporaryFolder.newFile("recally_reject_drafts.preferences_pb")

            // First "process": a draft from an earlier session.
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val firstStore = newStore(storeFile, firstScope)
            firstStore.save(cardId = 7L, reason = "too trivial")
            firstScope.coroutineContext.job.cancelAndJoin()

            // Second "process": a fresh DataStore over the same file.
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val secondStore = newStore(storeFile, secondScope)
            assertEquals(
                "the draft from the earlier session is still there",
                mapOf(7L to "too trivial"),
                secondStore.load(),
            )
            secondScope.coroutineContext.job.cancelAndJoin()
        }

    @Test
    fun test_drafts_are_kept_per_card() =
        runTest {
            val storeFile = temporaryFolder.newFile("recally_reject_drafts.preferences_pb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = newStore(storeFile, scope)

            store.save(cardId = 1L, reason = "too trivial")
            store.save(cardId = 2L, reason = "asks what the source never says")

            assertEquals(
                mapOf(
                    1L to "too trivial",
                    2L to "asks what the source never says",
                ),
                store.load(),
            )
            scope.coroutineContext.job.cancelAndJoin()
        }

    @Test
    fun test_blank_save_removes_the_draft() =
        runTest {
            val storeFile = temporaryFolder.newFile("recally_reject_drafts.preferences_pb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = newStore(storeFile, scope)

            store.save(cardId = 1L, reason = "too trivial")
            store.save(cardId = 1L, reason = " ")

            assertTrue(
                "a blank reason removes the draft",
                store.load().isEmpty(),
            )
            scope.coroutineContext.job.cancelAndJoin()
        }

    @Test
    fun test_clear_removes_only_the_named_cards() =
        runTest {
            val storeFile = temporaryFolder.newFile("recally_reject_drafts.preferences_pb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = newStore(storeFile, scope)

            store.save(cardId = 1L, reason = "one")
            store.save(cardId = 2L, reason = "two")
            store.save(cardId = 3L, reason = "three")
            store.clear(setOf(1L, 3L))

            assertEquals(
                "only the named cards lose their drafts (negative)",
                mapOf(2L to "two"),
                store.load(),
            )
            scope.coroutineContext.job.cancelAndJoin()
        }

    @Test
    fun test_unreadable_file_loads_no_drafts() =
        runTest {
            // A directory where the file belongs: opening it for reading
            // throws IOException, and the store must answer no drafts rather
            // than propagate — a lost convenience must not block the queue.
            val blocker = temporaryFolder.newFolder("recally_reject_drafts.preferences_pb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = newStore(blocker, scope)

            assertTrue(
                "a read failure answers no drafts and does not throw (negative)",
                store.load().isEmpty(),
            )
            scope.coroutineContext.job.cancelAndJoin()
        }
}

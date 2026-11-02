package dev.recally.data.drafts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.recally.di.IoDispatcher
import dev.recally.domain.repository.RejectReasonDraftsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The DataStore Preferences file behind [RejectReasonDraftsRepository]
 * (docs/android.md, "Screens → 3"; issue #343): `recally_reject_drafts`, one
 * `draft_<cardId>` string per card. It is its own file on purpose — the
 * Settings DataStore promises that file never holds plaintext, and a Room
 * table is out: `RecallyDatabase` rebuilds on a version bump
 * (`fallbackToDestructiveMigration`, di/DatabaseModule.kt) and would drop
 * unsent ratings with it.
 *
 * A draft is a convenience, so a storage failure answers nothing and drops
 * the write — it never throws into the queue or a rejection.
 */
class RejectReasonDraftStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : RejectReasonDraftsRepository {
        override suspend fun load(): Map<Long, String> =
            withContext(ioDispatcher) {
                try {
                    dataStore.data
                        .first()
                        .asMap()
                        .filterKeys { key -> key.name.startsWith(KEY_PREFIX) }
                        .mapKeys { (key, _) -> key.name.removePrefix(KEY_PREFIX).toLong() }
                        .mapValues { (_, value) -> value as String }
                } catch (_: Exception) {
                    emptyMap()
                }
            }

        override suspend fun save(
            cardId: Long,
            reason: String,
        ) {
            withContext(ioDispatcher) {
                try {
                    dataStore.edit { preferences ->
                        if (reason.isBlank()) {
                            preferences -= keyFor(cardId)
                        } else {
                            preferences[keyFor(cardId)] = reason
                        }
                    }
                } catch (_: Exception) {
                    // A lost convenience must not block the queue or a rejection.
                }
            }
        }

        override suspend fun clear(cardIds: Set<Long>) {
            withContext(ioDispatcher) {
                try {
                    dataStore.edit { preferences ->
                        cardIds.forEach { preferences -= keyFor(it) }
                    }
                } catch (_: Exception) {
                    // Same as save: drafts are a convenience.
                }
            }
        }

        private fun keyFor(cardId: Long): Preferences.Key<String> = stringPreferencesKey("$KEY_PREFIX$cardId")

        private companion object {
            const val KEY_PREFIX = "draft_"
        }
    }

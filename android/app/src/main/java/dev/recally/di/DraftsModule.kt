package dev.recally.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.recally.data.drafts.RejectReasonDraftStore
import dev.recally.domain.repository.RejectReasonDraftsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * The reject-reason drafts store (issue #343, docs/android.md "Screens → 3").
 * [RejectReasonDraftStore] reads and writes its own DataStore file,
 * `recally_reject_drafts`; the file is created here, next to the Settings
 * store's provider, so the two DataStore instances stay singletons over
 * distinct files.
 */
@Module
@InstallIn(SingletonComponent::class)
object DraftsModule {
    @Provides
    @Singleton
    fun provideRejectReasonDraftStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): RejectReasonDraftStore =
        RejectReasonDraftStore(
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + ioDispatcher),
                    produceFile = { context.preferencesDataStoreFile("recally_reject_drafts") },
                ),
            ioDispatcher = ioDispatcher,
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DraftsBindingsModule {
    @Binds
    @Singleton
    abstract fun bindRejectReasonDraftsRepository(store: RejectReasonDraftStore): RejectReasonDraftsRepository
}

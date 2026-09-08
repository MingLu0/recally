package dev.recally.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.recally.data.local.DueCardDao
import dev.recally.data.local.DueSummaryMetaDao
import dev.recally.data.local.RecallyDatabase
import dev.recally.data.sync.RatingOutboxDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): RecallyDatabase =
        Room
            .databaseBuilder(context, RecallyDatabase::class.java, "recally.db")
            // Pre-release single-user app: no migration is written while the
            // schema is still moving between roadmap steps; a version bump
            // rebuilds the local cache. The only pre-existing install is v1,
            // which had no outbox table, so no un-flushed rating is lost.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDueCardDao(database: RecallyDatabase): DueCardDao = database.dueCardDao()

    @Provides
    fun provideDueSummaryMetaDao(database: RecallyDatabase): DueSummaryMetaDao = database.dueSummaryMetaDao()

    @Provides
    fun provideRatingOutboxDao(database: RecallyDatabase): RatingOutboxDao = database.ratingOutboxDao()
}

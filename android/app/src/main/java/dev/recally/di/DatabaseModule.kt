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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): RecallyDatabase = Room.databaseBuilder(context, RecallyDatabase::class.java, "recally.db").build()

    @Provides
    fun provideDueCardDao(database: RecallyDatabase): DueCardDao = database.dueCardDao()

    @Provides
    fun provideDueSummaryMetaDao(database: RecallyDatabase): DueSummaryMetaDao = database.dueSummaryMetaDao()
}

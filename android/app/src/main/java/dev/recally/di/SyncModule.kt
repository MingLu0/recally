package dev.recally.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.recally.data.sync.FlushScheduler
import dev.recally.data.sync.WorkManagerFlushScheduler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    @Singleton
    abstract fun bindFlushScheduler(impl: WorkManagerFlushScheduler): FlushScheduler
}

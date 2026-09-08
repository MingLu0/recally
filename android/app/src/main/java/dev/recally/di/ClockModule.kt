package dev.recally.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * The wall clock as a dependency, so the review session's flip-to-rate
 * `response_ms` clock and its in-session re-queue timer run on a fake in
 * tests.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}

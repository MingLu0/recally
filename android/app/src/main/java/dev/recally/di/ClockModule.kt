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
 * tests. Device-local rather than UTC: the Stats forecast anchors "today" to
 * the phone's calendar day, and the duration uses (`response_ms`, re-queue
 * timers) are zone-independent.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}

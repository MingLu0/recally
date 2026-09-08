package dev.recally.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.recally.data.repository.CardRepositoryImpl
import dev.recally.data.repository.DeckRepositoryImpl
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.DeckRepository
import javax.inject.Singleton

/**
 * Binds repository interfaces (domain/repository) to their implementations
 * (data/repository) so tests substitute a fake with no network and no
 * database (docs/android.md, "Repositories own the data layer").
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindCardRepository(impl: CardRepositoryImpl): CardRepository

    @Binds
    @Singleton
    abstract fun bindDeckRepository(impl: DeckRepositoryImpl): DeckRepository
}

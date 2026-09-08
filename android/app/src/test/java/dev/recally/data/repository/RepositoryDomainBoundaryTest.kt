package dev.recally.data.repository

import dev.recally.data.local.DueCardEntity
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.DeckRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layering is a hard boundary (docs/android.md, "Architecture"): ui → domain →
 * data. The repository interfaces are the seam, so their public surface may
 * only expose domain/model types — a Room entity or Retrofit DTO in a
 * signature here would reach every ViewModel.
 */
class RepositoryDomainBoundaryTest {
    @Test
    fun test_dto_never_reaches_domain() {
        val repositories =
            listOf(
                CardRepository::class.java,
                DeckRepository::class.java,
                dev.recally.domain.repository.StatsRepository::class.java,
                ApprovalRepository::class.java,
            )
        repositories.forEach { repositoryInterface ->
            assertEquals(
                "repository interfaces live in domain/repository",
                "dev.recally.domain.repository",
                repositoryInterface.`package`?.name,
            )
            repositoryInterface.declaredMethods.forEach { method ->
                // toGenericString() carries the full signature, including the
                // Continuation's type argument for suspend functions.
                assertFalse(
                    "${repositoryInterface.simpleName}.${method.name} exposes a data-layer type: ${method.toGenericString()}",
                    method.toGenericString().contains("dev.recally.data"),
                )
            }
        }

        // Meta-assertion: the check has teeth. A surface that does leak a
        // data-layer type must be flagged by the assertion above.
        assertTrue(
            "the boundary check must flag a leaky signature",
            LeakyRepository::class.java.declaredMethods.any {
                it.toGenericString().contains("dev.recally.data")
            },
        )
    }

    private interface LeakyRepository {
        fun leak(): DueCardEntity
    }
}

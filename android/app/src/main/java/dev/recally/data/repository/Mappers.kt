package dev.recally.data.repository

import dev.recally.data.local.DueCardEntity
import dev.recally.data.local.DueSummaryMetaEntity
import dev.recally.data.remote.DeckDto
import dev.recally.data.remote.DueCardDto
import dev.recally.data.remote.DueCardsResponse
import dev.recally.domain.model.Card
import dev.recally.domain.model.Deck
import dev.recally.domain.model.DueSummary
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * DTO/entity ↔ domain mapping. This is the only place the three type families
 * meet, so a data-layer type never reaches the domain surface
 * (docs/android.md, "Architecture").
 */
private val listJson = Json { ignoreUnknownKeys = true }

fun DueCardDto.toDomain(): Card =
    Card(
        id = id,
        unitId = unitId,
        type = type,
        front = front,
        back = back,
        bookId = bookId,
        book = book,
        chapter = chapter.orEmpty(),
        tags = tags,
        state = state,
        step = step,
        due = Instant.parse(due),
    )

fun DueCardsResponse.toDomain(): DueSummary =
    DueSummary(
        dueCount = dueCount,
        newCount = newCount,
        learningStepsMinutes = learningStepsMinutes,
        cards = cards.map { it.toDomain() },
    )

fun DeckDto.toDomain(): Deck =
    Deck(
        bookId = bookId,
        title = title,
        total = total,
        due = due,
    )

fun DueCardEntity.toDomain(): Card =
    Card(
        id = id,
        unitId = unitId,
        type = type,
        front = front,
        back = back,
        bookId = bookId,
        book = book,
        chapter = chapter,
        tags = listJson.decodeFromString(tagsJson),
        state = state,
        step = step,
        due = due?.let(Instant::parse),
    )

fun Card.toEntity(): DueCardEntity =
    DueCardEntity(
        id = id,
        unitId = unitId,
        type = type,
        front = front,
        back = back,
        bookId = bookId,
        book = book,
        chapter = chapter,
        tagsJson = listJson.encodeToString(tags),
        state = state,
        step = step,
        due = due?.toString(),
    )

fun DueSummary.toMetaEntity(): DueSummaryMetaEntity =
    DueSummaryMetaEntity(
        dueCount = dueCount,
        newCount = newCount,
        learningStepsJson = listJson.encodeToString(learningStepsMinutes),
    )

fun DueSummaryMetaEntity.toDomain(cards: List<Card>): DueSummary =
    DueSummary(
        dueCount = dueCount,
        newCount = newCount,
        learningStepsMinutes = listJson.decodeFromString(learningStepsJson),
        cards = cards,
    )

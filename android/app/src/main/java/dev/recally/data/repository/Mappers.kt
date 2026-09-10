package dev.recally.data.repository

import dev.recally.data.local.DueCardEntity
import dev.recally.data.local.DueSummaryMetaEntity
import dev.recally.data.remote.DeckCardDto
import dev.recally.data.remote.DeckDto
import dev.recally.data.remote.DueCardDto
import dev.recally.data.remote.DueCardsResponse
import dev.recally.data.remote.ForecastDayDto
import dev.recally.data.remote.PendingCardDto
import dev.recally.data.remote.PendingCardsResponse
import dev.recally.data.remote.PendingCountsDto
import dev.recally.data.remote.RateResponse
import dev.recally.data.remote.StatsResponse
import dev.recally.domain.model.Card
import dev.recally.domain.model.Deck
import dev.recally.domain.model.DeckCard
import dev.recally.domain.model.DueSummary
import dev.recally.domain.model.ForecastDay
import dev.recally.domain.model.PendingCard
import dev.recally.domain.model.PendingCounts
import dev.recally.domain.model.PendingQueue
import dev.recally.domain.model.RateOutcome
import dev.recally.domain.model.Stats
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

fun PendingCardDto.toDomain(): PendingCard =
    PendingCard(
        id = id,
        status = status,
        type = type,
        front = front,
        back = back,
        statusReason = statusReason,
        sourceHighlights = sourceHighlights,
        truncated = truncated,
        bookId = bookId,
        book = book,
        chapter = chapter.orEmpty(),
    )

fun PendingCardsResponse.toDomain(): PendingQueue =
    PendingQueue(
        cards = cards.map { it.toDomain() },
        counts = counts.toDomain(),
    )

fun PendingCountsDto.toDomain(): PendingCounts =
    PendingCounts(
        pendingReview = pendingReview,
        needsHuman = needsHuman,
    )

fun DeckDto.toDomain(): Deck =
    Deck(
        bookId = bookId,
        title = title,
        total = total,
        due = due,
        progress = progress,
        chapters = chapters,
        truncated = truncated,
    )

fun StatsResponse.toDomain(): Stats =
    Stats(
        streakDays = streakDays,
        reviewsToday = reviewsToday,
        retention30d = retention30d,
        lapseRateByType = lapseRateByType,
        lapseRateByGuidanceVersion = lapseRateByGuidanceVersion,
        curationYield = curationYield,
        nextDueAt = nextDueAt?.let(Instant::parse),
        forecast = forecast.map { it.toDomain() },
    )

fun ForecastDayDto.toDomain(): ForecastDay =
    ForecastDay(
        date = date,
        due = due,
    )

fun DeckCardDto.toDomain(): DeckCard =
    DeckCard(
        id = id,
        type = type,
        front = front,
        back = back,
        chapter = chapter,
        tags = tags,
        suspendedUntil = suspendedUntil?.let(Instant::parse),
        state = state,
        due = due?.let(Instant::parse),
    )

fun RateResponse.toDomain(): RateOutcome =
    RateOutcome(
        cardId = cardId,
        nextDue = nextDue.let(Instant::parse),
        state = state,
        step = step,
        lapsed = lapsed,
        duplicate = duplicate,
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

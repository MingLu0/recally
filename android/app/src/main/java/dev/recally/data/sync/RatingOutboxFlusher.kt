package dev.recally.data.sync

import android.util.Log
import dev.recally.data.remote.RateBatchItemRequest
import dev.recally.data.remote.RateBatchRequest
import dev.recally.data.remote.RecallyApi
import dev.recally.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Flushes the rating outbox via `POST /reviews/rate-batch`
 * (docs/api-spec.md; docs/android.md, "Offline-first sync").
 *
 * Dequeue policy:
 * - every item that came back `ok` is dequeued — `duplicate: true` is
 *   success, not an error, so a retried flush is safe;
 * - an item that failed with a 4xx `status` is dropped (and logged) rather
 *   than retried forever;
 * - a 5xx item stays queued for the next flush;
 * - a body that does not parse at all is a top-level 422 — the whole batch is
 *   discarded rather than re-flushed.
 *
 * Results are matched to queued rows **by position**: the response has one
 * result per submitted rating in request order, and two ratings for the same
 * card make `card_id` matching ambiguous. Rows are deleted only once the
 * server has acknowledged them.
 */
class RatingOutboxFlusher
    @Inject
    constructor(
        private val api: RecallyApi,
        private val dao: RatingOutboxDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun flush(): FlushResult =
            withContext(ioDispatcher) {
                val rows = dao.getAll()
                if (rows.isEmpty()) return@withContext FlushResult.Completed(acked = 0, dropped = 0)

                val request =
                    RateBatchRequest(
                        rows.map { row ->
                            RateBatchItemRequest(
                                cardId = row.cardId,
                                rating = row.rating,
                                responseMs = row.responseMs,
                                ratedAt = row.ratedAt,
                                deviceId = row.deviceId,
                            )
                        },
                    )

                val response =
                    try {
                        api.rateBatch(request)
                    } catch (exception: HttpException) {
                        if (exception.code() == HTTP_UNPROCESSABLE_ENTITY) {
                            Log.w(TAG, "rate-batch body rejected (422); discarding ${rows.size} queued rating(s)")
                            dao.deleteByIds(rows.map { it.id })
                            return@withContext FlushResult.Completed(acked = 0, dropped = rows.size)
                        }
                        Log.w(TAG, "rate-batch failed with HTTP ${exception.code()}; keeping ${rows.size} rating(s)")
                        return@withContext FlushResult.Retry(remaining = rows.size)
                    } catch (exception: IOException) {
                        Log.w(TAG, "rate-batch unreachable; keeping ${rows.size} rating(s)")
                        return@withContext FlushResult.Retry(remaining = rows.size)
                    } catch (exception: SerializationException) {
                        Log.w(TAG, "rate-batch response unparseable; discarding ${rows.size} queued rating(s)")
                        dao.deleteByIds(rows.map { it.id })
                        return@withContext FlushResult.Completed(acked = 0, dropped = rows.size)
                    }

                val acknowledgedIds = mutableListOf<Long>()
                val droppedIds = mutableListOf<Long>()
                rows.zip(response.results).forEach { (row, result) ->
                    when {
                        result.ok -> acknowledgedIds += row.id
                        result.status in HTTP_CLIENT_ERROR_RANGE -> {
                            Log.w(
                                TAG,
                                "dropping rating for card ${row.cardId}: server returned ${result.status} (${result.detail})",
                            )
                            droppedIds += row.id
                        }
                        // 5xx (or an unknown verdict): keep for the next flush.
                    }
                }
                dao.deleteByIds(acknowledgedIds + droppedIds)

                val remaining = dao.getAll().size
                if (remaining > 0) {
                    FlushResult.Retry(remaining = remaining)
                } else {
                    FlushResult.Completed(acked = acknowledgedIds.size, dropped = droppedIds.size)
                }
            }

        private companion object {
            const val TAG = "RatingOutboxFlusher"
            const val HTTP_UNPROCESSABLE_ENTITY = 422
            val HTTP_CLIENT_ERROR_RANGE = 400..499
        }
    }

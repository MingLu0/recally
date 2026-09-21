package dev.recally.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `ingest_runs` row `POST /ingest` and `GET /ingest/status` answer with
 * (docs/api-spec.md, "Ingestion"). The upload returns only after the
 * server-side pipeline has run, so `units_*`, `cards_generated` and
 * `cost_microusd` are filled by the time this arrives. The repository maps it
 * to the counts the import tile reports; the rest rides along for logs.
 */
@Serializable
data class IngestRunResponse(
    val filename: String,
    @SerialName("rows_seen") val rowsSeen: Int,
    @SerialName("rows_new") val rowsNew: Int,
    @SerialName("rows_updated") val rowsUpdated: Int,
    @SerialName("rows_unchanged") val rowsUnchanged: Int,
    @SerialName("rows_removed") val rowsRemoved: Int,
    @SerialName("units_kept") val unitsKept: Int,
    @SerialName("units_dropped") val unitsDropped: Int,
    @SerialName("highlights_dropped") val highlightsDropped: Int,
    @SerialName("cards_generated") val cardsGenerated: Int,
    @SerialName("cost_microusd") val costMicrousd: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    val error: String? = null,
)

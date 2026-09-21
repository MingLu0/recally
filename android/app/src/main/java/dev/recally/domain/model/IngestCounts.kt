package dev.recally.domain.model

/**
 * What one uploaded export did (docs/api-spec.md, "Ingestion") — the counts
 * the Decks import tile reports back after `POST /ingest` answers. The server
 * runs ingest *and* the agent pipeline before responding, so these are real
 * outcomes, not an "accepted".
 */
data class IngestCounts(
    val rowsNew: Int,
    val rowsUpdated: Int,
    val rowsRemoved: Int,
)

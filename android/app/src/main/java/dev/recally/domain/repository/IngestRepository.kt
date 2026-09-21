package dev.recally.domain.repository

import android.net.Uri
import dev.recally.domain.model.IngestCounts

/**
 * The upload half of ingestion (docs/android.md, *Screens → 4. Decks*). The
 * app is a transport and nothing more: it moves the picked file's bytes to
 * `POST /ingest` and never parses the CSV, extracts a UUID or decides what is
 * new — dedupe needs the whole `highlights` table, so only the server can do
 * it (hard rule 6).
 */
interface IngestRepository {
    /**
     * Upload one picked O'Reilly export. Streams from [uri]; the response
     * arrives after the server-side pipeline has run, which takes minutes for
     * a large export, so callers must be prepared to wait (docs/api-spec.md,
     * "Ingestion").
     */
    suspend fun uploadOReillyExport(uri: Uri): Result<IngestCounts>
}

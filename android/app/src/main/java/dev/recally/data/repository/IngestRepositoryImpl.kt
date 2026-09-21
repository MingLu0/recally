package dev.recally.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.apiCall
import dev.recally.di.IngestApi
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.IngestCounts
import dev.recally.domain.repository.IngestRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The transport half of the Decks import tile (docs/android.md, *Screens →
 * 4. Decks*): it streams the picked document to `POST /ingest` and reads back
 * what the export did. It never parses the CSV — no client-side UUID
 * extraction, no "what's new" — because dedupe needs the whole `highlights`
 * table and only the server has it (hard rule 6).
 *
 * The bytes flow straight from the content resolver into the socket: the part
 * body reports an unknown length unless the provider answers
 * `OpenableColumns.SIZE`, in which case OkHttp sends `Content-Length`;
 * otherwise the upload is chunked. Either way no byte array ever holds the
 * whole file.
 */
class IngestRepositoryImpl
    @Inject
    constructor(
        @IngestApi private val api: RecallyApi,
        @ApplicationContext context: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : IngestRepository {
        private val contentResolver: ContentResolver = context.contentResolver

        override suspend fun uploadOReillyExport(uri: Uri): Result<IngestCounts> =
            withContext(ioDispatcher) {
                val document = Document.open(contentResolver, uri)
                try {
                    val part =
                        MultipartBody.Part.createFormData(
                            name = "file",
                            filename = document.displayName,
                            body = document.requestBody(),
                        )
                    when (val result = apiCall { api.ingestExport(part) }) {
                        is Result.Success ->
                            Result.Success(
                                IngestCounts(
                                    rowsNew = result.data.rowsNew,
                                    rowsUpdated = result.data.rowsUpdated,
                                    rowsRemoved = result.data.rowsRemoved,
                                ),
                            )

                        is Result.Unauthorized -> Result.Unauthorized
                        is Result.HttpError -> result
                        is Result.NetworkError -> result
                        is Result.UnexpectedError -> result
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    Result.UnexpectedError(exception)
                } finally {
                    // Normally closed by writeTo; this covers the paths where
                    // the request never reached the wire (a rejected URL, say).
                    document.close()
                }
            }

        /**
         * A lazy [InputStream] over the picked document plus its display
         * name. The stream opens at construction (a rejected document fails
         * here, before any request is built) and closes when OkHttp finishes
         * writing the body.
         */
        private class Document(
            val displayName: String,
            private val stream: InputStream,
            private val size: Long?,
        ) {
            fun requestBody(): RequestBody =
                object : RequestBody() {
                    override fun contentType(): MediaType? = CSV_MEDIA_TYPE

                    override fun contentLength(): Long = size ?: -1L

                    override fun writeTo(sink: BufferedSink) {
                        stream.source().use { source -> sink.writeAll(source) }
                    }
                }

            /** Idempotent: writeTo closes the stream; this covers paths where it never ran. */
            fun close() {
                stream.close()
            }

            companion object {
                fun open(
                    resolver: ContentResolver,
                    uri: Uri,
                ): Document {
                    val stream =
                        resolver.openInputStream(uri)
                            ?: throw IllegalArgumentException("Cannot open picked document: $uri")
                    return Document(
                        displayName = resolver.displayName(uri) ?: FALLBACK_NAME,
                        stream = stream,
                        size = resolver.size(uri),
                    )
                }

                private fun ContentResolver.displayName(uri: Uri): String? = metadata(uri, OpenableColumns.DISPLAY_NAME)

                private fun ContentResolver.size(uri: Uri): Long? = metadata(uri, OpenableColumns.SIZE)?.toLongOrNull()

                private fun ContentResolver.metadata(
                    uri: Uri,
                    column: String,
                ): String? =
                    query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                    }
            }
        }

        private companion object {
            val CSV_MEDIA_TYPE: MediaType = "text/csv".toMediaType()
            const val FALLBACK_NAME = "oreilly-annotations.csv"
        }
    }

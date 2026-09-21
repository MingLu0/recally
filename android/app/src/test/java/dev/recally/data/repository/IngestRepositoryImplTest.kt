package dev.recally.data.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.recally.data.remote.ConnectionSettingsProvider
import dev.recally.data.remote.RecallyApiFactory
import dev.recally.domain.model.IngestCounts
import dev.recally.domain.repository.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The issue-#234 gate for `POST /ingest` client half. MockWebServer stands in
 * for the backend and a fake ContentProvider serves the picked document, so the
 * real OkHttp/Retrofit stack runs end to end: the bytes that leave the phone
 * are the bytes the provider held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IngestRepositoryImplTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: IngestRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // The provider answers ContentResolver queries the way the system
        // picker would: DISPLAY_NAME always, SIZE only for /known — the
        // /unknown document forces an unknown-length upload.
        Robolectric
            .buildContentProvider(ExportProvider::class.java)
            .create(
                ProviderInfo().apply { authority = ExportProvider.AUTHORITY },
            ).get()
        val settings =
            object : ConnectionSettingsProvider {
                override fun apiKey(): String? = TEST_API_KEY

                override fun baseUrl(): String? = server.url("/").toString()
            }
        repository =
            IngestRepositoryImpl(
                RecallyApiFactory.createIngest(settings),
                ApplicationProvider.getApplicationContext(),
                Dispatchers.IO,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `uploads the picked file as multipart`() =
        runTest {
            ExportProvider.exportBytes = CSV_BYTES
            enqueueIngestRun()

            val result = repository.uploadOReillyExport(ExportProvider.knownUri())

            assertEquals(
                Result.Success(IngestCounts(rowsNew = 15, rowsUpdated = 0, rowsRemoved = 0)),
                result,
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/ingest", request.path)
            assertTrue(
                "multipart request: ${request.headers["Content-Type"]}",
                request.headers["Content-Type"]?.startsWith("multipart/form-data") == true,
            )
            val body = request.body.readByteArray().decodeToString()
            assertTrue("the multipart field must be named file: $body", body.contains("name=\"file\""))
            assertTrue("the export's name rides along: $body", body.contains("filename=\"oreilly-annotations.csv\""))
            assertTrue("the file's bytes are the body: $body", body.contains(CSV_TEXT))
        }

    @Test
    fun `surfaces a 401 as an auth failure, not a generic error`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .addHeader("Content-Type", "application/problem+json")
                    .setBody("""{"status":401,"detail":"Invalid or missing X-API-Key header."}"""),
            )

            val result = repository.uploadOReillyExport(ExportProvider.knownUri())

            assertEquals(Result.Unauthorized, result)
        }

    @Test
    fun `surfaces a 422 as an invalid-export failure, not a generic error`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(422)
                    .addHeader("Content-Type", "application/problem+json")
                    .setBody("""{"status":422,"detail":"export.csv: missing column(s) Book Title"}"""),
            )

            val result = repository.uploadOReillyExport(ExportProvider.knownUri())

            assertEquals(
                Result.HttpError(status = 422, detail = "export.csv: missing column(s) Book Title"),
                result,
            )
        }

    @Test
    fun `never reads the file contents into memory`() =
        runTest {
            // A provider that cannot answer OpenableColumns.SIZE makes the
            // upload length unknown. OkHttp only omits Content-Length when the
            // body reports an unknown length — a client that buffered the file
            // to count its bytes would send one. A chunked request is the
            // proof the bytes flowed from the content resolver into the socket
            // without a byte array ever holding them all.
            ExportProvider.exportBytes = CSV_BYTES
            enqueueIngestRun()

            repository.uploadOReillyExport(ExportProvider.unknownSizeUri())

            val request = server.takeRequest()
            assertNull(
                "a streamed upload has no Content-Length: ${request.headers}",
                request.headers["Content-Length"],
            )
            assertEquals("chunked", request.headers["Transfer-Encoding"])
            assertTrue(
                request.body
                    .readByteArray()
                    .decodeToString()
                    .contains(CSV_TEXT),
            )
        }

    private fun enqueueIngestRun() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(INGEST_RUN_JSON),
        )
    }

    /** Stands in for the system document picker, serving [exportBytes]. */
    class ExportProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val size: Long? = if (uri.lastPathSegment == KNOWN) exportBytes.size.toLong() else null
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any?>(DISPLAY_NAME, size))
            }
        }

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            val pipe = ParcelFileDescriptor.createPipe()
            // Small payloads fit the pipe buffer, so writing before the reader
            // attaches is safe; closing the write end is what gives the reader
            // its EOF.
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(exportBytes) }
            return pipe[0]
        }

        override fun getType(uri: Uri): String? = "text/csv"

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            const val AUTHORITY = "dev.recally.test.export"
            const val DISPLAY_NAME = "oreilly-annotations.csv"
            private const val KNOWN = "known"
            var exportBytes: ByteArray = byteArrayOf()

            fun knownUri(): Uri = Uri.parse("content://$AUTHORITY/$KNOWN")

            fun unknownSizeUri(): Uri = Uri.parse("content://$AUTHORITY/unknown")
        }
    }

    private companion object {
        const val TEST_API_KEY = "test-api-key"
        const val CSV_TEXT = "Book Title,Chapter Title,Date of Highlight,Book URL,Annotation URL,Highlight"
        val CSV_BYTES = "$CSV_TEXT\n30 Agents,Ch 1,2026-09-01,https://example/9781806109012,https://ex#uuid,text".toByteArray()

        val INGEST_RUN_JSON =
            """
            {"filename":"oreilly-annotations.csv","rows_seen":15,"rows_new":15,
             "rows_updated":0,"rows_unchanged":0,"rows_removed":0,
             "units_kept":12,"units_dropped":1,"highlights_dropped":1,
             "cards_generated":30,"cost_microusd":42000,
             "started_at":"2026-09-21T08:00:00Z","finished_at":"2026-09-21T08:04:00Z","error":null}
            """.trimIndent()
    }
}

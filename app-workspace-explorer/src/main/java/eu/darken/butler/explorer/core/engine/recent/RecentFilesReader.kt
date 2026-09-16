package eu.darken.butler.explorer.core.engine.recent

import android.content.ContentResolver
import android.database.Cursor
import android.mtp.MtpConstants
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.mediastore.MediaStoreRowDecoder
import eu.darken.butler.common.files.mediastore.readMediaStoreRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * The files the media index has seen most recently, newest first.
 *
 * Only what MediaStore indexes is visible here. A file another app wrote into an arbitrary folder
 * is; app-private directories, root-only paths and network shares are not.
 */
@Singleton
class RecentFilesReader @Inject constructor(
    private val contentResolver: ContentResolver,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val tag = logTag("Explorer", "RecentFilesReader")

    /** [indexedAt] is the row's DATE_ADDED, or its DATE_MODIFIED where the provider left that unset. */
    data class Entry(
        val lookup: LocalPathLookup,
        val indexedAt: Instant,
    )

    /**
     * At most [limit] entries no older than [cutoff].
     *
     * Read in two passes rather than one `ORDER BY date_added DESC, date_modified DESC`: SQL sorts
     * every row without a usable `date_added` behind every row that has one, so a file the index
     * never stamped could be pushed past [limit] even while being the newest thing on the device.
     * Each pass covers one of the two cases and the merge re-ranks them together.
     */
    suspend fun read(cutoff: Instant, limit: Int): List<Entry> = withContext(dispatcherProvider.IO) {
        val cutoffSeconds = cutoff.epochSeconds.toString()
        val indexed = query(
            selection = "$COLUMN_ADDED > 0 AND $COLUMN_ADDED >= ? AND $NOT_A_DIRECTORY",
            selectionArgs = arrayOf(cutoffSeconds, FORMAT_DIRECTORY),
            sortOrder = "$COLUMN_ADDED DESC",
            limit = limit,
        )
        val unstamped = query(
            selection = "($COLUMN_ADDED IS NULL OR $COLUMN_ADDED <= 0) AND $COLUMN_MODIFIED >= ? AND $NOT_A_DIRECTORY",
            selectionArgs = arrayOf(cutoffSeconds, FORMAT_DIRECTORY),
            sortOrder = "$COLUMN_MODIFIED DESC",
            limit = limit,
        )
        log(tag, INFO) { "read(): ${indexed.size} indexed + ${unstamped.size} unstamped entries" }
        (indexed + unstamped)
            .sortedByDescending { it.indexedAt }
            .take(limit)
    }

    private suspend fun query(
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
        limit: Int,
    ): List<Entry> = coroutineScope {
        // A blocked ContentResolver.query() can't observe coroutine cancellation itself; this
        // sibling is cancelled the moment cancellation begins and trips the signal, which aborts
        // both a pending query and cursor window loads.
        val signal = CancellationSignal()
        val signalRelay = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                signal.cancel()
            }
        }

        try {
            val cursor = contentResolver.query(CONTENT_URI, PROJECTION, selection, selectionArgs, sortOrder, signal)
                ?: throw IOException("MediaStore query returned no cursor for $CONTENT_URI")
            cursor.use { collect(it, limit) }
        } catch (e: OperationCanceledException) {
            // Only translate when OUR signal fired due to coroutine cancellation; a provider-side
            // abort while we're still active is a real error.
            if (currentCoroutineContext().isActive) throw e
            throw CancellationException("MediaStore query cancelled", e)
        } finally {
            signalRelay.cancel()
        }
    }

    private suspend fun collect(cursor: Cursor, limit: Int): List<Entry> {
        val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

        val entries = mutableListOf<Entry>()
        var invalidRows = 0

        // MediaStore ignores a LIMIT in the sort string from API 30 on, so the cap is applied here.
        while (entries.size < limit && cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()

            val row = cursor.readMediaStoreRow(dataIndex, sizeIndex, modifiedIndex, addedIndex)
            val decoded = when (val outcome = MediaStoreRowDecoder.decode(row)) {
                is MediaStoreRowDecoder.Outcome.Decoded -> outcome
                // No usable DATA (e.g. redacted): expected on some devices, not an error
                is MediaStoreRowDecoder.Outcome.Unrepresentable -> continue
                is MediaStoreRowDecoder.Outcome.Invalid -> {
                    invalidRows++
                    continue
                }
            }

            val indexedAt = decoded.addedAt ?: decoded.lookup.modifiedAt ?: continue
            entries.add(Entry(lookup = decoded.lookup, indexedAt = indexedAt))
        }

        if (invalidRows > 0) log(tag) { "collect(): Skipped $invalidRows malformed rows" }
        return entries
    }

    companion object {
        /**
         * The literal volume name is deliberate: [MediaStore.VOLUME_EXTERNAL] has the same value but
         * only exists from API 29, and the literal avoids an API guard on a minSdk 26 module.
         */
        internal val CONTENT_URI = MediaStore.Files.getContentUri("external")

        internal val PROJECTION = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        private val COLUMN_ADDED = MediaStore.MediaColumns.DATE_ADDED
        private val COLUMN_MODIFIED = MediaStore.MediaColumns.DATE_MODIFIED

        /**
         * [MediaStore.Files.FileColumns].FORMAT is hidden in the public SDK, so the column is named
         * by its literal. Measured on API 34: the raw name is accepted in a selection, directory
         * rows carry 12289 and ordinary files carry 12288.
         */
        internal const val COLUMN_FORMAT = "format"

        private val FORMAT_DIRECTORY = MtpConstants.FORMAT_ASSOCIATION.toString()

        /**
         * The `IS NULL` half is load-bearing: `format != 12289` evaluates to NULL for a row whose
         * format column was never populated, which SQL treats as false and would drop that row.
         */
        private val NOT_A_DIRECTORY =
            "($COLUMN_FORMAT IS NULL OR $COLUMN_FORMAT != ?)"
    }
}

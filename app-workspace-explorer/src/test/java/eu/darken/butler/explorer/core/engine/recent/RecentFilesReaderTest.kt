package eu.darken.butler.explorer.core.engine.recent

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.mtp.MtpConstants
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentFilesReaderTest : BaseTest() {

    data class QueryRecord(
        val selection: String?,
        val selectionArgs: Array<String>?,
        val sortOrder: String?,
    )

    class FakeMediaProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor? {
            queries += QueryRecord(selection, selectionArgs, sortOrder)
            return cursorFactory(selection.orEmpty())
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

        companion object {
            var cursorFactory: (String) -> Cursor? = { null }
            val queries = mutableListOf<QueryRecord>()
        }
    }

    private lateinit var contentResolver: ContentResolver

    @Before
    fun setup() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        contentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        FakeMediaProvider.cursorFactory = { emptyCursor() }
        FakeMediaProvider.queries.clear()
    }

    private fun reader() = RecentFilesReader(
        contentResolver = contentResolver,
        dispatcherProvider = TestDispatcherProvider(),
    )

    private val columns = arrayOf(
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_ADDED,
    )

    private fun cursorOf(vararg rows: Array<Any?>): MatrixCursor =
        MatrixCursor(columns).apply { rows.forEach { addRow(it) } }

    private fun emptyCursor(): MatrixCursor = MatrixCursor(columns)

    /**
     * Fails the test rather than silently reading on if the limit did not stop the loop.
     * [moveToNext] and [moveToPosition] are both final on AbstractCursor; [onMove] is the hook they
     * funnel through, so the trap sits there.
     */
    private class CappedCursor(columns: Array<String>, private val allowedRows: Int) : MatrixCursor(columns) {
        override fun onMove(oldPosition: Int, newPosition: Int): Boolean {
            if (newPosition >= allowedRows) throw IllegalStateException("Cursor advanced past the limit")
            return super.onMove(oldPosition, newPosition)
        }
    }

    /**
     * Tells the two reads apart. A bare `IS NULL` would not: the directory clause is
     * `format IS NULL` and rides along on both selections, so the column has to be named.
     */
    private val unstampedMarker = "${MediaStore.MediaColumns.DATE_ADDED} IS NULL"

    private fun bySelection(indexed: () -> Cursor?, unstamped: () -> Cursor?): (String) -> Cursor? = { selection ->
        if (selection.contains(unstampedMarker)) unstamped() else indexed()
    }

    private val cutoff = Instant.fromEpochSeconds(1_600_000_000L)

    private fun row(path: String?, size: Long?, modified: Long?, added: Long?): Array<Any?> =
        arrayOf(path, size, modified, added)

    private suspend fun read(limit: Int = 100) = reader().read(cutoff = cutoff, limit = limit)

    @Test
    fun `both reads carry the cutoff and exclude directory rows`() = runTest {
        read()

        FakeMediaProvider.queries.size shouldBe 2
        FakeMediaProvider.queries.forEach { query ->
            query.selectionArgs!!.toList() shouldContainExactly listOf(
                "1600000000",
                MtpConstants.FORMAT_ASSOCIATION.toString(),
            )
            // Both halves are load-bearing: `format != 12289` is NULL for an unpopulated column,
            // which SQL treats as false and would drop the row.
            query.selection!! shouldContain "${RecentFilesReader.COLUMN_FORMAT} IS NULL"
            query.selection shouldContain "${RecentFilesReader.COLUMN_FORMAT} != ?"
        }

        // Guards every other test here: they route their cursors by this marker, and a marker that
        // matched both reads (or neither) would feed them the wrong one silently.
        FakeMediaProvider.queries.count { it.selection!!.contains(unstampedMarker) } shouldBe 1

        val indexed = FakeMediaProvider.queries.first { !it.selection!!.contains(unstampedMarker) }
        indexed.selection!! shouldContain "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        indexed.sortOrder shouldBe "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val unstamped = FakeMediaProvider.queries.first { it.selection!!.contains(unstampedMarker) }
        unstamped.selection!! shouldContain "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
        unstamped.sortOrder shouldBe "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
    }

    /**
     * The defect the two-read split exists for: a single `ORDER BY date_added DESC, date_modified
     * DESC` puts every unstamped row behind every stamped one, so this row would be cut off by the
     * limit even though it is the newest thing on the device.
     */
    @Test
    fun `an unstamped row newer than every indexed one survives a full first read`() = runTest {
        FakeMediaProvider.cursorFactory = bySelection(
            indexed = {
                cursorOf(
                    row("/storage/emulated/0/Download/a.pdf", 1L, 1_700_000_000L, 1_700_000_000L),
                    row("/storage/emulated/0/Download/b.pdf", 1L, 1_690_000_000L, 1_690_000_000L),
                )
            },
            unstamped = {
                cursorOf(row("/storage/emulated/0/Download/fresh.pdf", 1L, 1_710_000_000L, null))
            },
        )

        val entries = read(limit = 2)

        entries.map { it.lookup.name } shouldContainExactly listOf("fresh.pdf", "a.pdf")
        entries.first().indexedAt shouldBe Instant.fromEpochSeconds(1_710_000_000L)
    }

    @Test
    fun `a zero date_added is ranked by its modification time, like a null one`() = runTest {
        FakeMediaProvider.cursorFactory = bySelection(
            indexed = { cursorOf(row("/storage/emulated/0/Download/a.pdf", 1L, 1_700_000_000L, 1_700_000_000L)) },
            unstamped = { cursorOf(row("/storage/emulated/0/Download/zero.pdf", 1L, 1_710_000_000L, 0L)) },
        )

        val entries = read()

        entries.map { it.lookup.name } shouldContainExactly listOf("zero.pdf", "a.pdf")
        entries.first().indexedAt shouldBe Instant.fromEpochSeconds(1_710_000_000L)
    }

    @Test
    fun `each read stops its cursor loop at the limit`() = runTest {
        FakeMediaProvider.cursorFactory = bySelection(
            indexed = {
                CappedCursor(columns, allowedRows = 2).apply {
                    addRow(row("/storage/emulated/0/Download/a.pdf", 1L, 1_700_000_000L, 1_700_000_000L))
                    addRow(row("/storage/emulated/0/Download/b.pdf", 1L, 1_690_000_000L, 1_690_000_000L))
                    addRow(row("/storage/emulated/0/Download/c.pdf", 1L, 1_680_000_000L, 1_680_000_000L))
                }
            },
            unstamped = { emptyCursor() },
        )

        read(limit = 2).map { it.lookup.name } shouldContainExactly listOf("a.pdf", "b.pdf")
    }

    @Test
    fun `malformed and pathless rows are skipped without aborting the read`() = runTest {
        FakeMediaProvider.cursorFactory = bySelection(
            indexed = {
                cursorOf(
                    row("relative/path.pdf", 1L, 1_700_000_000L, 1_700_000_000L),
                    row(null, 1L, 1_699_000_000L, 1_699_000_000L),
                    row("/storage/emulated/0/Download/ok.pdf", 1L, 1_698_000_000L, 1_698_000_000L),
                )
            },
            unstamped = { emptyCursor() },
        )

        read().map { it.lookup.name } shouldContainExactly listOf("ok.pdf")
    }

    /** Neither timestamp means there is nothing to rank the row by, so it cannot be listed. */
    @Test
    fun `a row without any usable timestamp is skipped`() = runTest {
        FakeMediaProvider.cursorFactory = bySelection(
            indexed = { emptyCursor() },
            unstamped = { cursorOf(row("/storage/emulated/0/Download/undated.pdf", 1L, null, null)) },
        )

        read() shouldBe emptyList()
    }

    @Test
    fun `sizes and index times survive the decode`() = runTest {
        FakeMediaProvider.cursorFactory = bySelection(
            indexed = { cursorOf(row("/storage/emulated/0/Download/a.pdf", 4096L, 1_690_000_000L, 1_700_000_000L)) },
            unstamped = { emptyCursor() },
        )

        val entry = read().single()

        entry.lookup.size shouldBe 4096L
        entry.lookup.modifiedAt shouldBe Instant.fromEpochSeconds(1_690_000_000L)
        entry.indexedAt shouldBe Instant.fromEpochSeconds(1_700_000_000L)
    }
}

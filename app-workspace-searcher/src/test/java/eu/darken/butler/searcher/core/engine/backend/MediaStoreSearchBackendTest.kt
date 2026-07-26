package eu.darken.butler.searcher.core.engine.backend

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ApiLevel
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.MetadataRepo
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.engine.ContentMatcher
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchFilter
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreSearchBackendTest : BaseTest() {

    class FakeMediaProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor? {
            queriedUris += uri
            queriedProjections += projection
            return cursorFactory(uri)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

        companion object {
            var cursorFactory: (Uri) -> Cursor? = { null }
            val queriedUris = mutableListOf<Uri>()
            val queriedProjections = mutableListOf<Array<String>?>()
        }
    }

    private lateinit var contentResolver: ContentResolver
    private val contentMatcher = mockk<ContentMatcher>()
    private val progressUpdates = mutableListOf<SearchBackend.ScanProgress>()

    @Before
    fun setup() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        contentResolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        FakeMediaProvider.cursorFactory = { null }
        FakeMediaProvider.queriedUris.clear()
        FakeMediaProvider.queriedProjections.clear()
        progressUpdates.clear()
    }

    private fun apiLevel(sdk: Int): ApiLevel = mockk<ApiLevel> {
        every { has(any()) } answers { firstArg<Int>() <= sdk }
    }

    private fun backend(sdk: Int = 34, resolver: ContentResolver = contentResolver) = MediaStoreSearchBackend(
        contentResolver = resolver,
        metadataRepo = mockk<MetadataRepo> {
            coEvery { extract(any()) } returns null
        },
        dispatcherProvider = TestDispatcherProvider(),
        matcher = SearchItemMatcher(contentMatcher),
        pathPermissionCheck = mockk(),
        apiLevel = apiLevel(sdk),
    )

    private fun mediaCursor(vararg rows: Array<Any?>): MatrixCursor {
        val cursor = MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
        )
        rows.forEach { cursor.addRow(it) }
        return cursor
    }

    private fun session(
        query: SearchQuery,
        collection: SearchTarget.MediaStore.Collection = SearchTarget.MediaStore.Collection.IMAGES,
    ) = SearchBackend.ScanSession(
        workspaceId = Workspace.Id(),
        target = SearchTarget.MediaStore(collection),
        query = query,
        includeBinaries = false,
        onProgress = { progressUpdates += it },
    )

    private fun query(
        filename: String? = null,
        filter: SearchFilter = SearchFilter(),
    ) = SearchQuery(
        filenameQuery = filename?.let { FilenameQuery(pattern = it) } ?: FilenameQuery(),
        targets = emptyList(),
        filter = filter,
    )

    @Test
    fun `happy path maps rows to index-ranked file results`() = runTest {
        FakeMediaProvider.cursorFactory = {
            mediaCursor(
                arrayOf("/storage/emulated/0/DCIM/needle.jpg", 1234L, 1_700_000_000L),
                arrayOf("/storage/emulated/0/DCIM/other.jpg", 99L, 1_700_000_000L),
            )
        }

        val results = backend().scan(session(query(filename = "needle"))).toList()

        val result = results.single()
        result.sourceRank shouldBe SearchBackend.BackendResult.RANK_INDEX
        result.item.path shouldBe LocalPath.build("/storage/emulated/0/DCIM/needle.jpg")
        result.item.fileType shouldBe FileType.FILE
        result.item.size shouldBe 1234L
        result.item.lookup.createdAt shouldBe null

        val finalProgress = progressUpdates.last()
        finalProgress.itemsScanned shouldBe 2
        finalProgress.resultsFound shouldBe 1
        finalProgress.errorCount shouldBe 0
        finalProgress.currentPath shouldBe null
    }

    @Test
    fun `filter conditions are evaluated against decoded rows`() = runTest {
        FakeMediaProvider.cursorFactory = {
            mediaCursor(
                arrayOf("/storage/emulated/0/small.jpg", 50L, null),
                arrayOf("/storage/emulated/0/big.jpg", 200L, null),
            )
        }
        val filter = SearchFilter(
            conditions = listOf(
                FilterCondition.Size(
                    eu.darken.butler.workspace.contracts.searcher.FilterComparator.GT,
                    100L,
                )
            )
        )

        val results = backend().scan(session(query(filter = filter))).toList()

        results.map { it.item.path.path } shouldContainExactly listOf("/storage/emulated/0/big.jpg")
    }

    @Test
    fun `directory type filter yields nothing - collections only index files`() = runTest {
        FakeMediaProvider.cursorFactory = {
            mediaCursor(arrayOf("/storage/emulated/0/a.jpg", 10L, null))
        }
        val filter = SearchFilter(conditions = listOf(FilterCondition.Type(FileType.DIRECTORY)))

        backend().scan(session(query(filter = filter))).toList() shouldBe emptyList()
    }

    @Test
    fun `null and relative DATA rows are skipped without aborting the scan`() = runTest {
        FakeMediaProvider.cursorFactory = {
            mediaCursor(
                arrayOf(null, 10L, null),
                arrayOf("relative/needle.jpg", 10L, null),
                arrayOf("/storage/emulated/0/needle.jpg", 10L, null),
            )
        }

        val results = backend().scan(session(query(filename = "needle"))).toList()

        results.map { it.item.path.path } shouldContainExactly listOf("/storage/emulated/0/needle.jpg")
        val finalProgress = progressUpdates.last()
        // Null DATA is expected (redacted rows) and silent; the relative path counts as an error
        finalProgress.errorCount shouldBe 1
        finalProgress.itemsScanned shouldBe 3
    }

    @Test
    fun `null cursor fails the target`() = runTest {
        FakeMediaProvider.cursorFactory = { null }

        shouldThrow<IOException> {
            backend().scan(session(query(filename = "x"))).toList()
        }
    }

    @Test
    fun `provider exception fails the target`() = runTest {
        FakeMediaProvider.cursorFactory = { throw SecurityException("nope") }

        shouldThrow<SecurityException> {
            backend().scan(session(query(filename = "x"))).toList()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `downloads collection is unsupported below api 29`() = runTest {
        shouldThrow<UnsupportedCollectionException> {
            backend(sdk = 28)
                .scan(session(query(filename = "x"), collection = SearchTarget.MediaStore.Collection.DOWNLOADS))
                .toList()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `media collections work below api 29 via legacy uris`() = runTest {
        FakeMediaProvider.cursorFactory = {
            mediaCursor(arrayOf("/storage/emulated/0/needle.mp3", 10L, null))
        }

        val results = backend(sdk = 28)
            .scan(session(query(filename = "needle"), collection = SearchTarget.MediaStore.Collection.AUDIO))
            .toList()

        results.single().item.path.path shouldBe "/storage/emulated/0/needle.mp3"
    }

    @Test
    fun `requirements are empty for unavailable collections`() = runTest {
        val requirements = backend(sdk = 28)
            .monitorRequirements(SearchTarget.MediaStore(SearchTarget.MediaStore.Collection.DOWNLOADS))
            .first()

        requirements.needsSetup shouldBe false
    }

    @Test
    fun `modern uris and minimal projection are used on api 29+`() = runTest {
        FakeMediaProvider.cursorFactory = { mediaCursor() }

        backend().scan(session(query(filename = "x"), collection = SearchTarget.MediaStore.Collection.DOWNLOADS))
            .toList()

        FakeMediaProvider.queriedUris.single() shouldBe
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        FakeMediaProvider.queriedProjections.single()?.toList() shouldBe listOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
    }

    @Test
    @Config(sdk = [28])
    fun `legacy uris are used below api 29`() = runTest {
        FakeMediaProvider.cursorFactory = { mediaCursor() }

        backend(sdk = 28)
            .scan(session(query(filename = "x"), collection = SearchTarget.MediaStore.Collection.AUDIO))
            .toList()

        FakeMediaProvider.queriedUris.single() shouldBe
            @Suppress("DEPRECATION") android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    @Test
    fun `provider-side OperationCanceledException while active is a target error not a cancellation`() = runTest {
        FakeMediaProvider.cursorFactory = { throw android.os.OperationCanceledException("provider abort") }

        shouldThrow<android.os.OperationCanceledException> {
            backend().scan(session(query(filename = "x"))).toList()
        }
    }

    @Test
    fun `cancelling the collector fires the cancellation signal and stops a blocked query`() {
        // Robolectric's shadow resolver doesn't route CancellationSignal to providers, so the
        // resolver is mocked directly: it blocks like a real provider stuck loading a cursor
        // window until OUR signal is cancelled, then aborts with OperationCanceledException.
        val queryStarted = java.util.concurrent.CountDownLatch(1)
        val signalCancelled = java.util.concurrent.CountDownLatch(1)
        val blockingResolver = mockk<ContentResolver> {
            every { query(any(), any(), any(), any(), any(), any()) } answers {
                val signal = arg<android.os.CancellationSignal?>(5)
                // The answer is not a suspending function, so this is a plain blocking wait for
                // an explicit signal. setOnCancelListener fires immediately if the signal was
                // already cancelled, so the notification cannot be missed.
                signal?.setOnCancelListener { signalCancelled.countDown() }
                queryStarted.countDown()
                // Watchdog only: a broken relay fails the join timeout below instead of hanging
                signalCancelled.await(30, java.util.concurrent.TimeUnit.SECONDS)
                signal?.throwIfCanceled()
                mediaCursor()
            }
        }

        kotlinx.coroutines.runBlocking {
            val scanJob = this.launch(kotlinx.coroutines.Dispatchers.IO) {
                backend(resolver = blockingResolver).scan(session(query(filename = "x"))).toList()
            }
            // Explicit signals, the timeouts are watchdogs against a hang
            queryStarted.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
            scanJob.cancel()
            // The relay coroutine must cancel the signal, which aborts the blocked provider call
            signalCancelled.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
            kotlinx.coroutines.withTimeout(30_000) { scanJob.join() }
            scanJob.isCancelled shouldBe true
        }
    }
}

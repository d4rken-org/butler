package eu.darken.butler.workspace.core.operations.history

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDatabase
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryScopeEntity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Ingest behavior against a real (in-memory) database: what ends up in the audit table, what ends up
 * in the scope index, and what the operation row summarizes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperationHistoryPersistTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val completedOperations = MutableSharedFlow<CompletedOperationSnapshot>(replay = 1)
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var database: OperationHistoryDatabase
    private lateinit var dao: SignalingHistoryDao
    private lateinit var repo: OperationHistoryRepo

    private val source = LocalPath.build("/sdcard/Download/photo.jpg")
    private val destinationFolder = LocalPath.build("/sdcard/Backup")
    private val created = LocalPath.build("/sdcard/Backup/photo.jpg")

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            context,
            OperationHistoryDatabase::class.java,
        ).build()
        dao = SignalingHistoryDao(database.operationHistoryDao())
        repo = createHistoryRepo(
            context = context,
            database = database,
            dao = dao,
            appScope = appScope,
            completedOperations = completedOperations,
        )
    }

    @After
    fun teardown() {
        appScope.cancel()
        database.close()
    }

    private suspend fun persist(snapshot: CompletedOperationSnapshot): String {
        completedOperations.emit(snapshot)
        return dao.inserts.first()
    }

    private fun idsForScopes(vararg scopes: String): List<String> {
        val query = OperationHistoryRepo.buildScopedIdsQueryStatic(
            outcomes = HistoryOutcome.entries.map { it.name },
            kinds = Operation.Metadata.Kind.entries.map { it.name },
            pathScopes = scopes.toList(),
            limit = 100,
        )
        return database.openHelper.writableDatabase.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    /** Bulk filler for the size-based retention: one operation carrying [scopeRows] index rows. */
    private suspend fun seedBulk(id: String, completedAt: Instant, scopeRows: Int) {
        database.operationHistoryDao().insertWithPathsAndTrim(
            entry = OperationHistoryEntity(
                id = id,
                kind = Operation.Metadata.Kind.DELETE.name,
                intent = null,
                originType = HistoryEntry.OriginType.EXPLORER.name,
                originWorkspaceId = "ws",
                title = "title",
                description = "description",
                summary = null,
                startedAt = completedAt,
                completedAt = completedAt,
                durationMs = 0,
                outcome = HistoryOutcome.COMPLETED.name,
                errorMessage = null,
                errorClass = null,
                affectedPathsCount = 0,
            ),
            paths = emptyList(),
            scopePaths = (0 until scopeRows).map { index ->
                OperationHistoryScopeEntity(
                    operationHistoryId = id,
                    path = "/sdcard/Bulk/$id/nested/folder/file_$index.bin",
                    sortIndex = index,
                )
            },
            maxItems = 1000,
        )
    }

    @Test
    fun `a successful single-file copy reports only the created file`() = runTest {
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.COPY,
                    plan = planInto(source, destination = destinationFolder),
                ),
                state = TestCompletedState(
                    report = TestReport(
                        affectedPaths = listOf(
                            changeOf(created, Operation.Report.PathChange.Change.ADDED),
                        ),
                    ),
                ),
            )
        )

        val stored = database.operationHistoryDao().getById(id)!!
        stored.paths.size shouldBe 1
        stored.paths.single().path shouldBe created.path
        stored.paths.single().change shouldBe Operation.Report.PathChange.Change.ADDED.name

        // The source was only read and the destination folder only gained a child - neither is a change.
        stored.paths.map { it.path } shouldNotContain source.path
        stored.paths.map { it.path } shouldNotContain destinationFolder.path
    }

    @Test
    fun `an install is stored under its own kind and named by its container`() = runTest {
        val container = LocalPath.build("/sdcard/Download/example.apk")
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.INSTALL,
                    plan = planOver(container),
                ),
                state = TestCompletedState(report = null),
            )
        )

        val stored = database.operationHistoryDao().getById(id)!!
        stored.entry.kind shouldBe Operation.Metadata.Kind.INSTALL.name
        stored.entry.outcome shouldBe HistoryOutcome.COMPLETED.name
        // An install writes no file of its own, so the container is all there is to name the row by.
        stored.paths.shouldBeEmpty()
        stored.entry.primaryPath shouldBe container.path
    }

    @Test
    fun `the scope index keeps sources, destinations and their parents`() = runTest {
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.COPY,
                    plan = planInto(source, destination = destinationFolder),
                ),
                state = TestCompletedState(
                    report = TestReport(
                        affectedPaths = listOf(
                            changeOf(created, Operation.Report.PathChange.Change.ADDED),
                        ),
                    ),
                ),
            )
        )

        allScopePaths(id).map { it.path } shouldContainAll listOf(
            source.path,
            destinationFolder.path,
            created.path,
            "/sdcard/Download",
            "/sdcard",
        )
    }

    @Test
    fun `a failed copy reports no changes but keeps a scope and a primary path`() = runTest {
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.COPY,
                    plan = planInto(source, destination = destinationFolder),
                ),
                state = TestCompletedState(
                    report = null,
                    error = IOException("No space left on device"),
                ),
            )
        )

        val stored = database.operationHistoryDao().getById(id)!!
        stored.paths.shouldBeEmpty()
        stored.entry.affectedPathsCount shouldBe 0
        stored.entry.outcome shouldBe HistoryOutcome.FAILED.name
        stored.entry.primaryPath shouldBe source.path

        allScopePaths(id).map { it.path } shouldContainAll listOf(
            source.path,
            destinationFolder.path,
        )
    }

    @Test
    fun `a failed delete never invents a removal`() = runTest {
        val target = LocalPath.build("/sdcard/protected/notes.txt")
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.DELETE,
                    plan = planOver(target),
                ),
                state = TestCompletedState(
                    report = null,
                    error = IOException("Permission denied"),
                ),
            )
        )

        database.operationHistoryDao().getById(id)!!.paths.shouldBeEmpty()
        allScopePaths(id).map { it.path } shouldContainAll listOf(target.path, "/sdcard/protected")
    }

    @Test
    fun `the affected count derives from reported changes only`() = runTest {
        val reported = (1..3).map { LocalPath.build("/sdcard/Backup/file_$it.txt") }
        val sources = (1..7).map { LocalPath.build("/sdcard/Download/file_$it.txt") }
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.COPY,
                    plan = planInto(*sources.toTypedArray(), destination = destinationFolder),
                ),
                state = TestCompletedState(
                    report = TestReport(
                        affectedPaths = reported.map {
                            changeOf(it, Operation.Report.PathChange.Change.ADDED)
                        },
                    ),
                ),
            )
        )

        val stored = database.operationHistoryDao().getById(id)!!
        stored.entry.affectedPathsCount shouldBe 3
        stored.entry.pathsTruncated shouldBe false
        stored.paths.size shouldBe 3
        stored.entry.primaryPath shouldBe reported.first().path
        // Both child sets land with the operation row.
        allScopePaths(id).size shouldBe 13
    }

    @Test
    fun `an exact path stays scopable even when every path has its own parent`() = runTest {
        // 101 distinct parents: the parent rows alone used to consume the whole per-operation budget,
        // dropping the last exact directories - and an ancestor row can't stand in for them.
        val directories = (1..101).map { LocalPath.build("/sdcard/Parent_$it/dir_$it") }
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.DELETE,
                    plan = planOver(*directories.toTypedArray()),
                ),
                state = TestCompletedState(
                    report = TestReport(
                        affectedPaths = directories.map {
                            changeOf(it, Operation.Report.PathChange.Change.REMOVED)
                        },
                    ),
                ),
            )
        )

        val lastDirectory = directories.last().path
        allScopePaths(id).map { it.path } shouldContain lastDirectory
        idsForScopes(lastDirectory) shouldContainExactly listOf(id)
    }

    /*
     * The two producers that override OperationPathPlan.scopePaths, pinned as exact ordered row
     * lists - this is the only place their overrides are observable, and the attempted-paths sheet
     * renders exactly this order.
     */

    private val savedA = LocalPath.build("/sdcard/Backup/a.txt")
    private val savedB = LocalPath.build("/sdcard/Backup/b.txt")
    private val archived = LocalPath.build("/sdcard/Backup/bundle.zip")
    private val downloadA = LocalPath.build("/sdcard/Download/a.txt")
    private val downloadB = LocalPath.build("/sdcard/Download/b.txt")

    /** Mirrors SaveFilesOperation: the planned files are the scope, the target directory is not. */
    private fun saveFilesPlan() = OperationPathPlan(
        targets = listOf(savedA, savedB),
        destination = OperationPathPlan.Destination.Container(destinationFolder),
        scopePaths = listOf(savedA, savedB),
    )

    /** Mirrors CompressOperation: the destination DIRECTORY is the scope, not the archive path. */
    private fun compressPlan() = OperationPathPlan(
        targets = listOf(downloadA, downloadB),
        destination = OperationPathPlan.Destination.RequestedTarget(archived),
        scopePaths = listOf(downloadA, downloadB, destinationFolder),
    )

    @Test
    fun `a successful save indexes the written files and their folder, nothing above it`() = runTest {
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.SAVE,
                    plan = saveFilesPlan(),
                ),
                state = TestCompletedState(
                    report = TestReport(
                        affectedPaths = listOf(
                            changeOf(savedA, Operation.Report.PathChange.Change.ADDED),
                            changeOf(savedB, Operation.Report.PathChange.Change.ADDED),
                        ),
                    ),
                ),
            )
        )

        allScopePaths(id).map { it.path } shouldContainExactly listOf(
            "/sdcard/Backup",
            "/sdcard/Backup/a.txt",
            "/sdcard/Backup/b.txt",
        )
    }

    @Test
    fun `a failed save indexes the same rows a successful one would`() = runTest {
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.SAVE,
                    plan = saveFilesPlan(),
                ),
                state = TestCompletedState(
                    report = null,
                    error = IOException("No space left on device"),
                ),
            )
        )

        allScopePaths(id).map { it.path } shouldContainExactly listOf(
            "/sdcard/Backup",
            "/sdcard/Backup/a.txt",
            "/sdcard/Backup/b.txt",
        )
        database.operationHistoryDao().getById(id)!!.entry.primaryPath shouldBe savedA.path
    }

    @Test
    fun `a successful compression indexes the sources, the destination folder and the archive`() = runTest {
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.COMPRESS,
                    plan = compressPlan(),
                ),
                state = TestCompletedState(
                    report = TestReport(
                        affectedPaths = listOf(
                            changeOf(archived, Operation.Report.PathChange.Change.ADDED),
                        ),
                    ),
                ),
            )
        )

        allScopePaths(id).map { it.path } shouldContainExactly listOf(
            "/sdcard/Download",
            "/sdcard",
            "/sdcard/Backup",
            "/sdcard/Download/a.txt",
            "/sdcard/Download/b.txt",
            "/sdcard/Backup/bundle.zip",
        )
    }

    @Test
    fun `a failed compression still indexes the destination folder`() = runTest {
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.COMPRESS,
                    plan = compressPlan(),
                ),
                state = TestCompletedState(
                    report = null,
                    error = IOException("No space left on device"),
                ),
            )
        )

        allScopePaths(id).map { it.path } shouldContainExactly listOf(
            "/sdcard/Download",
            "/sdcard",
            "/sdcard/Download/a.txt",
            "/sdcard/Download/b.txt",
            "/sdcard/Backup",
        )
        database.operationHistoryDao().getById(id)!!.entry.primaryPath shouldBe downloadA.path
    }

    @Test
    fun `the database is trimmed back under the size limit`() = runTest {
        val now = Clock.System.now()
        val ids = (1..40).map { "op-%02d".format(it) }
        ids.forEachIndexed { index, id -> seedBulk(id, now - (40 - index).minutes, scopeRows = 60) }

        val limit = 128L * 1024
        repo.trimToMaxBytes(limitBytes = limit)

        val remaining = database.operationHistoryDao()
        remaining.getById(ids.first()) shouldBe null
        remaining.getById(ids.last()) shouldNotBe null

        val count = remaining.getCount()
        count shouldBeGreaterThanOrEqualTo 1
        count shouldBeLessThan ids.size

        usedBytes() shouldBeLessThanOrEqualTo limit
    }

    @Test
    fun `a file left oversized by an unreclaimed trim is shrunk on the next trim`() = runTest {
        val now = Clock.System.now()
        val ids = (1..40).map { "op-%02d".format(it) }
        ids.forEachIndexed { index, id -> seedBulk(id, now - (40 - index).minutes, scopeRows = 60) }

        // Rows dropped without reclaiming the pages: what a VACUUM that failed with SQLITE_BUSY
        // leaves behind - the content fits the limit, the file doesn't.
        database.operationHistoryDao().deleteOldest(ids.size - 1)

        val limit = usedBytes() + pageSize()
        totalBytes() shouldBeGreaterThan limit

        repo.trimToMaxBytes(limitBytes = limit)

        totalBytes() shouldBeLessThanOrEqualTo limit
        // Space came from the freelist, not from dropping the remaining operation.
        database.operationHistoryDao().getCount() shouldBe 1
    }

    @Test
    fun `an oversized file with light fragmentation is still reclaimed`() = runTest {
        val now = Clock.System.now()
        val ids = (1..40).map { "op-%02d".format(it) }
        ids.forEachIndexed { index, id -> seedBulk(id, now - (40 - index).minutes, scopeRows = 300) }

        // Only a few operations dropped: enough free pages to shrink the file, far too few for the
        // 25% fragmentation heuristic - so a VACUUM only happens if being over the limit triggers it.
        database.operationHistoryDao().deleteOldest(3)
        val survivors = database.operationHistoryDao().getCount()

        val limit = usedBytes() + pageSize()
        // The window this test guards: the trim loop deletes nothing (content already fits), the
        // file is still over the limit, and fragmentation stays below the 25% heuristic.
        freeBytes() shouldBeGreaterThan 0L
        totalBytes() shouldBeGreaterThan limit
        (freeBytes() * 4) shouldBeLessThanOrEqualTo totalBytes()

        repo.trimToMaxBytes(limitBytes = limit)

        totalBytes() shouldBeLessThanOrEqualTo limit
        // Space came from the freelist, not from dropping more operations.
        database.operationHistoryDao().getCount() shouldBe survivors
    }

    @Test
    fun `the attempted paths sheet only loads a bounded preview`() = runTest {
        val scopeRows = OperationHistoryRepo.MAX_PATHS_PER_OP + 137
        seedBulk("op-bulk", Clock.System.now(), scopeRows = scopeRows)

        val attempted = repo.getAttemptedPaths("op-bulk")
        attempted.paths.size shouldBe OperationHistoryRepo.MAX_PATHS_PER_OP
        attempted.totalCount shouldBe scopeRows
        // The index itself stays uncapped so filtering still sees every path.
        allScopePaths("op-bulk").size shouldBe scopeRows
    }

    /** The scope index is uncapped, the preview query isn't - assertions on it need the full set. */
    private suspend fun allScopePaths(id: String) = dao.getScopePathsPreview(id, Int.MAX_VALUE)

    private fun usedBytes(): Long = database.openHelper.writableDatabase.let { db ->
        (db.pragmaLong("page_count") - db.pragmaLong("freelist_count")) * db.pragmaLong("page_size")
    }

    private fun totalBytes(): Long = database.openHelper.writableDatabase.let { db ->
        db.pragmaLong("page_count") * db.pragmaLong("page_size")
    }

    private fun freeBytes(): Long = database.openHelper.writableDatabase.let { db ->
        db.pragmaLong("freelist_count") * db.pragmaLong("page_size")
    }

    private fun pageSize(): Long = database.openHelper.writableDatabase.pragmaLong("page_size")

    private fun SupportSQLiteDatabase.pragmaLong(pragma: String): Long =
        query("PRAGMA $pragma").use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
}

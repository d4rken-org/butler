package eu.darken.butler.workspace.core.operations.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDao
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDatabase
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryPathEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryScopeEntity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Bulk delete and the list query executed against real SQLite. Both split their id list into
 * chunks, and only a database that actually enforces `SQLITE_MAX_VARIABLE_NUMBER` semantics can
 * show that the split covers every id and still orders the whole set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperationHistoryBulkDeleteTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val completedOperations = MutableSharedFlow<CompletedOperationSnapshot>(replay = 1)
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val baseTime = Clock.System.now() - 3.seconds * OVER_LIMIT

    private lateinit var database: OperationHistoryDatabase
    private lateinit var dao: OperationHistoryDao
    private lateinit var repo: OperationHistoryRepo

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            context,
            OperationHistoryDatabase::class.java,
        ).build()
        dao = database.operationHistoryDao()
        repo = createHistoryRepo(
            context = context,
            database = database,
            dao = dao,
            appScope = appScope,
            completedOperations = completedOperations,
            maxItems = OVER_LIMIT * 2,
        )
    }

    @After
    fun teardown() {
        appScope.cancel()
        database.close()
    }

    /** One entry with one path row and one scope row each, oldest first. */
    private suspend fun seed(id: String, ageSteps: Int) {
        dao.insertEntry(
            OperationHistoryEntity(
                id = id,
                kind = Operation.Metadata.Kind.COPY.name,
                intent = null,
                originType = HistoryEntry.OriginType.EXPLORER.name,
                originWorkspaceId = "ws",
                title = "title",
                description = "description",
                summary = null,
                startedAt = baseTime + ageSteps.seconds,
                completedAt = baseTime + ageSteps.seconds,
                durationMs = 0,
                outcome = HistoryOutcome.COMPLETED.name,
                errorMessage = null,
                errorClass = null,
                affectedPathsCount = 1,
            )
        )
        dao.insertPaths(
            listOf(
                OperationHistoryPathEntity(
                    operationHistoryId = id,
                    path = "/sdcard/$id.txt",
                    previousPath = null,
                    change = Operation.Report.PathChange.Change.ADDED.name,
                    sortIndex = 0,
                )
            )
        )
        dao.insertScopePaths(
            listOf(
                OperationHistoryScopeEntity(
                    operationHistoryId = id,
                    path = "/sdcard/$id.txt",
                    sortIndex = 0,
                )
            )
        )
    }

    private suspend fun seedOverLimit(): List<String> {
        val ids = (1..OVER_LIMIT).map { "op-%04d".format(it) }
        ids.forEachIndexed { index, id -> seed(id, ageSteps = index) }
        return ids
    }

    private fun rowCount(table: String): Int = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM $table")
        .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Test
    fun `deleting more entries than SQLite can bind removes all of them`() = runTest {
        val doomed = seedOverLimit()
        seed("op-keeper", ageSteps = OVER_LIMIT + 1)

        dao.deleteByIdsChunked(doomed)

        dao.getCount() shouldBe 1
        rowCount("operation_history_paths") shouldBe 1
        rowCount("operation_history_scope") shouldBe 1
    }

    @Test
    fun `a query matching more entries than SQLite can bind returns them all newest-first`() = runTest {
        val seeded = seedOverLimit()

        val entries = repo.query(HistoryFilter(), limit = OVER_LIMIT).first()

        entries.map { it.id } shouldContainExactly seeded.reversed()
        entries.forEach { entry ->
            entry.paths.map { it.path } shouldContainExactly listOf("/sdcard/${entry.id}.txt")
        }
    }

    companion object {
        private val OVER_LIMIT = OperationHistoryDao.MAX_BIND_ARGS + 100
    }
}

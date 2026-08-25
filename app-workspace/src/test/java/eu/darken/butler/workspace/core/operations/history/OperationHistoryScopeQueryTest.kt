package eu.darken.butler.workspace.core.operations.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDatabase
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryScopeEntity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
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

/**
 * The path-scope filter executed against real SQLite. SQL-text assertions can't prove matching
 * behavior: literal `%`, `_` and `\` in a scope, the root scope and prefix-sharing siblings all
 * depend on how the bound values are interpreted, not on how the statement reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperationHistoryScopeQueryTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val completedOperations = MutableSharedFlow<CompletedOperationSnapshot>(replay = 1)
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var database: OperationHistoryDatabase
    private lateinit var dao: SignalingHistoryDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            context,
            OperationHistoryDatabase::class.java,
        ).build()
        dao = SignalingHistoryDao(database.operationHistoryDao())
        createHistoryRepo(
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

    private suspend fun seed(id: String, scopePaths: List<String>) {
        val now = Clock.System.now()
        database.operationHistoryDao().insertWithPathsAndTrim(
            entry = OperationHistoryEntity(
                id = id,
                kind = Operation.Metadata.Kind.COPY.name,
                intent = null,
                originType = HistoryEntry.OriginType.EXPLORER.name,
                originWorkspaceId = "ws",
                title = "title",
                description = "description",
                summary = null,
                startedAt = now,
                completedAt = now,
                durationMs = 0,
                outcome = HistoryOutcome.COMPLETED.name,
                errorMessage = null,
                errorClass = null,
                affectedPathsCount = 0,
            ),
            paths = emptyList(),
            scopePaths = scopePaths.mapIndexed { index, path ->
                OperationHistoryScopeEntity(
                    operationHistoryId = id,
                    path = path,
                    sortIndex = index,
                )
            },
            maxItems = 1000,
        )
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

    @Test
    fun `the root scope matches every recorded path`() = runTest {
        seed("op-root", listOf("/"))
        seed("op-deep", listOf("/sdcard/Music/song.mp3"))

        idsForScopes("/") shouldContainExactlyInAnyOrder listOf("op-root", "op-deep")
    }

    @Test
    fun `a percent in the scope is matched literally`() = runTest {
        seed("op-percent", listOf("/foo%bar", "/foo%bar/inner.txt"))
        seed("op-other", listOf("/fooXbar", "/fooXbar/inner.txt"))

        idsForScopes("/foo%bar") shouldContainExactly listOf("op-percent")
    }

    @Test
    fun `an underscore in the scope is matched literally`() = runTest {
        seed("op-underscore", listOf("/foo_bar/inner.txt"))
        seed("op-other", listOf("/fooZbar/inner.txt"))

        idsForScopes("/foo_bar") shouldContainExactly listOf("op-underscore")
    }

    @Test
    fun `a backslash in the scope is matched literally`() = runTest {
        seed("op-backslash", listOf("/back\\slash", "/back\\slash/inner.txt"))
        seed("op-other", listOf("/backXslash/inner.txt"))

        idsForScopes("/back\\slash") shouldContainExactly listOf("op-backslash")
    }

    @Test
    fun `a sibling folder sharing a prefix does not match`() = runTest {
        seed("op-music", listOf("/sdcard/Music", "/sdcard/Music/song.mp3"))
        seed("op-musicians", listOf("/sdcard/Musicians", "/sdcard/Musicians/song.mp3"))

        val matches = idsForScopes("/sdcard/Music")
        matches shouldContainExactly listOf("op-music")
        matches shouldNotContain "op-musicians"
    }

    @Test
    fun `multiple scopes are OR-joined`() = runTest {
        seed("op-a", listOf("/sdcard/A/file.txt"))
        seed("op-b", listOf("/sdcard/B/file.txt"))
        seed("op-c", listOf("/sdcard/C/file.txt"))

        idsForScopes("/sdcard/A", "/sdcard/B") shouldContainExactlyInAnyOrder listOf("op-a", "op-b")
    }

    @Test
    fun `planned sources and reported destinations both reach the index`() = runTest {
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.COPY,
                    plan = planInto(
                        LocalPath.build("/sdcard/Old/file.txt"),
                        destination = LocalPath.build("/sdcard/New"),
                    ),
                ),
                state = TestCompletedState(
                    report = TestReport(
                        affectedPaths = listOf(
                            changeOf(
                                LocalPath.build("/sdcard/New/file.txt"),
                                Operation.Report.PathChange.Change.ADDED,
                            ),
                        ),
                    ),
                ),
            )
        )

        idsForScopes("/sdcard/Old") shouldContainExactly listOf(id)
        idsForScopes("/sdcard/New") shouldContainExactly listOf(id)
    }

    @Test
    fun `a move source reaches the index even when the path plan never named it`() = runTest {
        val id = persist(
            testSnapshot(
                metadata = testMetadata(
                    operationKind = Operation.Metadata.Kind.MOVE,
                    plan = planInto(
                        LocalPath.build("/sdcard/Current/notes.txt"),
                        destination = LocalPath.build("/sdcard/New"),
                    ),
                    operationIntent = Operation.Metadata.Intent.RENAME,
                ),
                state = TestCompletedState(
                    report = TestReport(
                        affectedPaths = listOf(
                            changeOf(
                                path = LocalPath.build("/sdcard/New/renamed.txt"),
                                change = Operation.Report.PathChange.Change.MOVED,
                                previousPath = LocalPath.build("/sdcard/Legacy/original.txt"),
                            ),
                        ),
                    ),
                ),
            )
        )

        idsForScopes("/sdcard/Legacy") shouldContainExactly listOf(id)
    }
}

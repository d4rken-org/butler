package eu.darken.butler.workspace.core.operations.history

import android.content.Context
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDao
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDatabase
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryPathEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryScopeEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import testhelpers.mockDataStoreValue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/*
 * Shared harness for the Room-backed history tests: fake operation snapshots plus a repo wired to a
 * real DAO. The repo ingests via OperationsManager.completedOperations, so tests emit a snapshot and
 * await the DAO write via [SignalingHistoryDao].
 */

internal class TestReport(
    override val affectedPaths: Collection<Operation.Report.PathChange>,
    override val subjectPath: APath<*>? = null,
    override val partialErrorCount: Int = 0,
    override val summary: CaString = "report".toCaString(),
) : Operation.Report

internal class TestCompletedState(
    override val report: Operation.Report?,
    override val error: Throwable? = null,
    override val startedAt: Instant = Clock.System.now() - 5.seconds,
    override val completedAt: Instant = Clock.System.now(),
    override val summary: CaString = "summary".toCaString(),
) : Operation.State.Completed

internal fun testMetadata(
    operationKind: Operation.Metadata.Kind,
    plan: OperationPathPlan? = null,
    operationIntent: Operation.Metadata.Intent? = null,
): Operation.Metadata = mockk<Operation.Metadata>().apply {
    every { origin } returns Operation.Metadata.Origin.Explorer(Workspace.Id())
    every { icon } returns mockk()
    every { title } returns "title".toCaString()
    every { description } returns "description".toCaString()
    every { kind } returns operationKind
    every { intent } returns operationIntent
    every { pathPlan } returns plan
}

/** The plan a copy/move producer builds: sources plus the directory they land in. */
internal fun planInto(
    vararg sources: APath<*>,
    destination: APath<*>,
) = OperationPathPlan(
    targets = sources.toList(),
    destination = OperationPathPlan.Destination.Container(destination),
)

/** The plan a delete/create producer builds: targets only, no destination. */
internal fun planOver(vararg targets: APath<*>) = OperationPathPlan(targets = targets.toList())

internal fun testSnapshot(
    metadata: Operation.Metadata,
    state: Operation.State.Completed,
) = CompletedOperationSnapshot(
    id = Operation.Id(),
    metadata = metadata,
    state = state,
)

internal fun changeOf(
    path: APath<*>,
    change: Operation.Report.PathChange.Change,
    previousPath: APath<*>? = null,
) = Operation.Report.PathChange(
    path = path,
    change = change,
    previousPath = previousPath,
)

/** Signals every completed write so tests can await the repo's ingest instead of polling. */
internal class SignalingHistoryDao(
    private val delegate: OperationHistoryDao,
) : OperationHistoryDao by delegate {

    val inserts = MutableSharedFlow<String>(replay = 8)

    override suspend fun insertWithPathsAndTrim(
        entry: OperationHistoryEntity,
        paths: List<OperationHistoryPathEntity>,
        scopePaths: List<OperationHistoryScopeEntity>,
        maxItems: Int,
    ) {
        delegate.insertWithPathsAndTrim(entry, paths, scopePaths, maxItems)
        inserts.emit(entry.id)
    }
}

internal fun createHistoryRepo(
    context: Context,
    database: OperationHistoryDatabase,
    dao: OperationHistoryDao,
    appScope: CoroutineScope,
    completedOperations: MutableSharedFlow<CompletedOperationSnapshot>,
    maxItems: Int = 500,
) = OperationHistoryRepo(
    appScope = appScope,
    context = context,
    operationsManager = mockk<OperationsManager>().apply {
        every { this@apply.completedOperations } returns completedOperations
    },
    database = database,
    dao = dao,
    historySettings = mockk<HistorySettings>().apply {
        every { saveHistory } returns mockDataStoreValue(true)
        every { maxHistoryItems } returns mockDataStoreValue(maxItems)
    },
)

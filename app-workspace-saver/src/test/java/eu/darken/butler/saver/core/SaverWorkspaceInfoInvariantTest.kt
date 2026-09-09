package eu.darken.butler.saver.core

import android.net.Uri
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.saver.core.operations.SaveFilesOperation
import eu.darken.butler.saver.core.operations.SaveFilesReport
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant

/**
 * [Workspace.Info.activeCount] is documented as never exceeding [Workspace.Info.operationCount], and
 * the running spinner in the rail and the tab manager is drawn from `activeCount > 0` before
 * `operationCount` is looked at. A Saver tab is the one place the two counters come from separate
 * inputs of the same `combine` - the save's own [SaverWorkspace.SaveState] and the operation list -
 * so an emission triggered by one of them carries the other's cached value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaverWorkspaceInfoInvariantTest {

    private val workspaceId = Workspace.Id()

    private val operationState = MutableStateFlow<Operation.State>(
        Operation.State.Queued(startedAt = Instant.fromEpochSeconds(0))
    )

    private val operationsFlow = MutableStateFlow<List<ManagedOperation>>(emptyList())

    private val managed = mockk<ManagedOperation>().apply {
        every { id } returns Operation.Id()
        every { state } returns operationState
        every { metadata } returns mockk {
            every { origin } returns Operation.Metadata.Origin.Saver(workspaceId)
        }
    }

    private val activeState = SaveFilesOperation.State.Active(
        startedAt = Instant.fromEpochSeconds(0),
    )

    private val completedState = SaveFilesOperation.State.Completed(
        startedAt = Instant.fromEpochSeconds(0),
        completedAt = Instant.fromEpochSeconds(1),
        error = null,
        report = SaveFilesReport(results = emptyList()),
    )

    private fun makeWorkspace(): SaverWorkspace {
        val sourceInfo = ContentUriHelper.SourceInfo(
            uri = mockk<Uri>(),
            displayName = "app.apk",
            size = 4L,
            mimeType = "application/vnd.android.package-archive",
            isAccessible = true,
        )
        return SaverWorkspace(
            id = workspaceId,
            arguments = SaverArguments.Default(
                sourceUris = listOf("content://provider/app.apk"),
                destinationPath = LocalPath.build("/save"),
            ),
            dispatcherProvider = TestDispatcherProvider(),
            contentUriHelper = mockk<ContentUriHelper> { every { extractInfo(any()) } returns sourceInfo },
            operationsManager = mockk<OperationsManager> {
                every { operations } returns operationsFlow
                coEvery { submitManaged(any()) } returns managed
            },
            issueHandler = mockk<IssueHandler>(relaxed = true),
            saveFilesOperationFactory = mockk<SaveFilesOperation.Factory> {
                every { create(any(), any()) } returns mockk<SaveFilesOperation>(relaxed = true)
            },
            pkgOps = mockk<PkgOps>(relaxed = true),
            json = mockk<Json>(relaxed = true),
            storageEnvironment = mockk<StorageEnvironment>(relaxed = true),
        )
    }

    /** Every `(operationCount, activeCount)` pair the tab published, in order. */
    private fun CoroutineScope.recordInfo(
        workspace: SaverWorkspace,
        into: MutableList<Pair<Int, Int>>,
    ) = launch {
        workspace.info.collect { into += it.operationCount to it.activeCount }
    }

    private fun assertInvariantHeld(recorded: List<Pair<Int, Int>>) {
        // Printed so a failure shows the whole sequence, not just the offending pair.
        println("Workspace.Info emissions (operationCount, activeCount): $recorded")
        val violations = recorded
            .filter { (operationCount, activeCount) -> activeCount > operationCount }
            .map { (operationCount, activeCount) -> "operationCount=$operationCount activeCount=$activeCount" }
        violations shouldBe emptyList()
    }

    /**
     * The operation reaches [OperationsManager.operations] only after `save()` has attached its own
     * collector to the operation's state. That is the ordering the two subscriptions really race
     * for: `save()` attaches in a single `launchIn`, while the counting side has to travel
     * `operations` -> `operationsForWorkspace` -> `withOnlyStateChanges`' `flatMapLatest` before it
     * subscribes at all.
     */
    @Test
    fun `a finished save never reports running work`() = runTest {
        val workspace = makeWorkspace()
        val recorded = mutableListOf<Pair<Int, Int>>()
        val recorder = CoroutineScope(Dispatchers.Unconfined)
        recorder.recordInfo(workspace, recorded)

        workspace.save()
        operationsFlow.value = listOf(managed)
        operationState.value = activeState
        operationState.value = completedState

        recorder.cancel()
        assertInvariantHeld(recorded)
    }

    /**
     * Order-independent companion to the test above: the save state is what leaves `Saving`, while
     * the operation is still writing. No scheduling is involved, so this pins the same defect
     * without depending on which collector wins.
     */
    @Test
    fun `a reset save state never leaves running work behind`() = runTest {
        operationsFlow.value = listOf(managed)
        val workspace = makeWorkspace()
        val recorded = mutableListOf<Pair<Int, Int>>()
        val recorder = CoroutineScope(Dispatchers.Unconfined)
        recorder.recordInfo(workspace, recorded)

        workspace.save()
        operationState.value = activeState
        workspace.resetSaveState()

        recorder.cancel()
        assertInvariantHeld(recorded)
    }
}

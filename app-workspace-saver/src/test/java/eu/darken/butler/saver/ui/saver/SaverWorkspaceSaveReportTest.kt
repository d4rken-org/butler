package eu.darken.butler.saver.ui.saver

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.saver.core.operations.SaveFilesReport
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Telling a caller where a save landed. The Saver keeps running afterwards - the event is
 * informational, and the user's "Open saved file" / "Save again" actions have to survive it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaverWorkspaceSaveReportTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val callerId = Workspace.Id()
    private val savedPath = LocalPath.build("/sdcard/Download/backup.zip")

    private val saveState = MutableStateFlow<SaverWorkspace.SaveState>(SaverWorkspace.SaveState.Idle)
    private val emitted = mutableListOf<WorkspaceEvent>()
    private val executed = mutableListOf<WorkspaceAction>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun success(vararg paths: LocalPath) = SaverWorkspace.SaveState.Success(
        SaveFilesReport(
            results = paths.map {
                SaveFilesReport.FileResult.Success(filename = it.name, savedPath = it, bytes = 4L)
            },
        ),
    )

    private fun makeViewModel(reportSavedPaths: Boolean): SaverWorkspaceViewModel {
        val workspace = mockk<SaverWorkspace>().apply {
            every { callerWorkspaceId } returns callerId
            every { this@apply.reportSavedPaths } returns reportSavedPaths
            every { saveState } returns this@SaverWorkspaceSaveReportTest.saveState
            every { state } returns MutableStateFlow(SaverWorkspace.State(callerWorkspaceId = callerId))
            every { currentOperation } returns flowOf(null)
        }
        val chrome = mockk<WorkspacePageChrome>(relaxed = true).apply {
            every { pendingConflicts } returns MutableStateFlow(emptyMap())
            every { shareIntentEvent } returns SingleEventFlow()
        }
        val remote = mockk<WorkspaceRemote> {
            every { events } returns emptyFlow()
            every { state } returns emptyFlow()
            coEvery { emitEvent(any()) } answers { emitted.add(firstArg()) }
            coEvery { execute(any()) } answers {
                executed.add(firstArg())
                WorkspaceAction.Create.Result.Success(Workspace.Id())
            }
        }
        return SaverWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
            workspaceProvider = mockk<WorkspaceProvider> {
                every { retrieve(workspaceId) } returns flowOf(workspace)
            },
            workspaceRemote = remote,
            storageEnvironment = mockk(relaxed = true),
            operationFocusRequest = OperationFocusRequest(),
            chromeFactory = mockk<WorkspacePageChrome.Factory> {
                every { create(any(), any<CoroutineScope>()) } returns chrome
            },
        )
    }

    private fun saveResults() = emitted.filterIsInstance<WorkspaceEvent.SaveResult>()

    @Test
    fun `a successful save reports where it wrote`() = runTest(UnconfinedTestDispatcher()) {
        makeViewModel(reportSavedPaths = true)

        saveState.value = SaverWorkspace.SaveState.Saving(1, 1, "backup.zip")
        saveState.value = success(savedPath)

        saveResults() shouldBe listOf(
            WorkspaceEvent.SaveResult(
                workspaceId = workspaceId,
                callerWorkspaceId = callerId,
                savedPaths = listOf(savedPath),
            ),
        )
    }

    /** The event is informational: closing here would take the Saver's own post-save UI away. */
    @Test
    fun `reporting never closes the saver`() = runTest(UnconfinedTestDispatcher()) {
        makeViewModel(reportSavedPaths = true)

        saveState.value = success(savedPath)

        executed.filterIsInstance<WorkspaceAction.Close>() shouldBe emptyList()
    }

    @Test
    fun `a saver nobody is waiting on stays quiet`() = runTest(UnconfinedTestDispatcher()) {
        // APK exports from Apps / App details launch a caller-owned Saver with nobody listening.
        makeViewModel(reportSavedPaths = false)

        saveState.value = success(savedPath)

        saveResults() shouldBe emptyList()
    }

    @Test
    fun `a save that was already finished when the page subscribed is not re-reported`() =
        runTest(UnconfinedTestDispatcher()) {
            saveState.value = success(savedPath)

            makeViewModel(reportSavedPaths = true)

            saveResults() shouldBe emptyList()
        }

    @Test
    fun `save again reports a second time`() = runTest(UnconfinedTestDispatcher()) {
        val second = LocalPath.build("/sdcard/Documents/backup.zip")
        makeViewModel(reportSavedPaths = true)

        saveState.value = success(savedPath)
        // "Save again" resets to Idle before the next run.
        saveState.value = SaverWorkspace.SaveState.Idle
        saveState.value = success(second)

        saveResults().map { it.savedPaths } shouldBe listOf(listOf(savedPath), listOf(second))
    }

    @Test
    fun `a run that wrote nothing reports an empty list`() = runTest(UnconfinedTestDispatcher()) {
        // SaveFilesOperation collects per-file failures into the report and still completes, so a
        // run that saved nothing arrives here as a success.
        makeViewModel(reportSavedPaths = true)

        saveState.value = success()

        saveResults().single().savedPaths shouldBe emptyList()
    }
}

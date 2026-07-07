package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerOperationConflictControllerTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    private fun CoroutineScope.controller(
        pendingConflicts: Flow<Map<Operation.Id, Issue>> = MutableStateFlow(emptyMap()),
        focusRequest: OperationFocusRequest = OperationFocusRequest(),
        workspace: ExplorerWorkspace = mockk<ExplorerWorkspace>().apply {
            coEvery { resolveConflict(any(), any()) } just Runs
        },
    ) = ExplorerOperationConflictController(
        workspaceId = workspaceId,
        pendingConflicts = pendingConflicts,
        operationFocusRequest = focusRequest,
        workspace = { workspace },
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `show sheet surfaces the pending conflict`() = runTest {
        val opId = Operation.Id()
        val issue = mockk<Issue>()
        val controller = controller(pendingConflicts = MutableStateFlow(mapOf(opId to issue)))

        controller.showSheet(opId)
        runCurrent()

        controller.issueState.value shouldBe issue
        controller.showIssueSheet.value shouldBe true
    }

    @Test
    fun `show sheet without a matching conflict is a no-op`() = runTest {
        val controller = controller(pendingConflicts = MutableStateFlow(emptyMap()))

        controller.showSheet(Operation.Id())
        runCurrent()

        controller.issueState.value shouldBe null
        controller.showIssueSheet.value shouldBe false
    }

    @Test
    fun `dismiss clears the sheet state`() = runTest {
        val opId = Operation.Id()
        val controller = controller(pendingConflicts = MutableStateFlow(mapOf(opId to mockk<Issue>())))
        controller.showSheet(opId)
        runCurrent()

        controller.dismissSheet()

        controller.issueState.value shouldBe null
        controller.showIssueSheet.value shouldBe false
    }

    @Test
    fun `resolve forwards to the workspace with the surfaced operation id`() = runTest {
        val opId = Operation.Id()
        val workspace = mockk<ExplorerWorkspace>().apply {
            coEvery { resolveConflict(any(), any()) } just Runs
        }
        val resolution = mockk<PathActionIssue.Resolution>()
        val controller = controller(
            pendingConflicts = MutableStateFlow(mapOf(opId to mockk<Issue>())),
            workspace = workspace,
        )
        controller.showSheet(opId)
        runCurrent()

        controller.resolve(resolution)
        runCurrent()

        coVerify { workspace.resolveConflict(opId, resolution) }
        controller.issueState.value shouldBe null
        controller.showIssueSheet.value shouldBe false
    }

    @Test
    fun `focus request waits for the conflict then surfaces and consumes it`() = runTest {
        val opId = Operation.Id()
        val issue = mockk<Issue>()
        val conflicts = MutableStateFlow<Map<Operation.Id, Issue>>(emptyMap())
        val focusRequest = OperationFocusRequest()
        val controller = controller(pendingConflicts = conflicts, focusRequest = focusRequest)

        val collector = controller.focusRequestHandler.launchIn(this)
        runCurrent()

        focusRequest.request(workspaceId, opId)
        runCurrent()
        // Conflict not present yet - nothing surfaced, request not consumed.
        controller.showIssueSheet.value shouldBe false
        focusRequest.requests.value shouldNotBe null

        conflicts.value = mapOf(opId to issue)
        runCurrent()

        controller.issueState.value shouldBe issue
        controller.showIssueSheet.value shouldBe true
        focusRequest.requests.value shouldBe null

        collector.cancel()
    }

    @Test
    fun `focus request for another workspace is ignored`() = runTest {
        val opId = Operation.Id()
        val conflicts = MutableStateFlow(mapOf(opId to mockk<Issue>()))
        val focusRequest = OperationFocusRequest()
        val controller = controller(pendingConflicts = conflicts, focusRequest = focusRequest)

        val collector = controller.focusRequestHandler.launchIn(this)
        runCurrent()

        focusRequest.request(Workspace.Id(), opId)
        runCurrent()

        controller.showIssueSheet.value shouldBe false
        collector.cancel()
    }
}

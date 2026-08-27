package eu.darken.butler.workspace.ui.page

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Clock

class WorkspacePageChromeTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    private fun waitingState(issue: Issue) = object : Operation.State.Waiting {
        override val startedAt = Clock.System.now()
        override val waitingSince = Clock.System.now()
        override val reason = "conflict".toCaString()
        override val issue = issue
    }

    private fun managedOp(stateFlow: MutableStateFlow<Operation.State>): ManagedOperation {
        val op = mockk<ManagedOperation>()
        every { op.id } returns Operation.Id()
        every { op.state } returns stateFlow
        every { op.canCancel } returns true
        every { op.metadata } returns mockk {
            every { origin } returns Operation.Metadata.Origin.Explorer(workspaceId)
            every { icon } returns mockk()
            every { title } returns "op".toCaString()
            every { description } returns "desc".toCaString()
            every { pathPlan } returns null
        }
        return op
    }

    private fun CoroutineScope.chrome(
        operationsManager: OperationsManager,
    ) = WorkspacePageChrome(
        workspaceId = workspaceId,
        scope = this,
        context = mockk(),
        clipboardRepo = mockk<ClipboardRepo>().apply {
            every { state } returns emptyFlow()
        },
        operationsManager = operationsManager,
        errorReportTool = mockk(),
        systemClipboardHelper = mockk(),
        workspaceRemote = mockk(),
    )

    // Regression: withStateUpdates() re-emits the SAME list instance on operation state changes.
    // A stateIn-based share conflates equal values, freezing state-only transitions
    // (Queued->Running->Waiting->Completed) for all collectors.
    @Test
    fun `operations re-emits on state-only transitions`() = runTest {
        val stateFlow = MutableStateFlow<Operation.State>(
            Operation.State.Queued(startedAt = Clock.System.now()),
        )
        val operationsManager = mockk<OperationsManager>().apply {
            every { operations } returns MutableStateFlow(listOf(managedOp(stateFlow)))
        }
        val chrome = backgroundScope.chrome(operationsManager)

        val emissions = mutableListOf<OperationsDisplayState>()
        val collector = chrome.operations.onEach { emissions.add(it) }.launchIn(backgroundScope)
        runCurrent()

        emissions.last().operations.single().state shouldBe OperationDisplay.State.Queued

        stateFlow.value = waitingState(mockk())
        runCurrent()

        emissions.last().operations.single().state.shouldBeInstanceOf<OperationDisplay.State.Waiting>()
        collector.cancel()
    }

    @Test
    fun `pendingConflicts surfaces conflicts arriving via state-only transitions`() = runTest {
        val stateFlow = MutableStateFlow<Operation.State>(
            Operation.State.Queued(startedAt = Clock.System.now()),
        )
        val op = managedOp(stateFlow)
        val operationsManager = mockk<OperationsManager>().apply {
            every { operations } returns MutableStateFlow(listOf(op))
        }
        val chrome = backgroundScope.chrome(operationsManager)

        val emissions = mutableListOf<Map<Operation.Id, Issue>>()
        val collector = chrome.pendingConflicts.onEach { emissions.add(it) }.launchIn(backgroundScope)
        runCurrent()

        emissions.last() shouldBe emptyMap()

        val issue = mockk<Issue>()
        stateFlow.value = waitingState(issue)
        runCurrent()

        emissions.last() shouldBe mapOf(op.id to issue)
        collector.cancel()
    }
}

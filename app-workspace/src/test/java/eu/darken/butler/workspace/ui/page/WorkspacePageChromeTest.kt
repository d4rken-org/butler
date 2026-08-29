package eu.darken.butler.workspace.ui.page

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.ErrorIncidentStore
import eu.darken.butler.common.error.ErrorReportPackager
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.error.PackagedErrorReport
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.error.recordingIncidentStore
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
        errorReportTool: ErrorReportTool = mockk(),
        errorReportPackager: ErrorReportPackager = mockk(),
        errorIncidentStore: ErrorIncidentStore = recordingIncidentStore(),
    ) = WorkspacePageChrome(
        workspaceId = workspaceId,
        scope = this,
        context = mockk(),
        clipboardRepo = mockk<ClipboardRepo>().apply {
            every { state } returns emptyFlow()
        },
        operationsManager = operationsManager,
        errorReportTool = errorReportTool,
        errorReportPackager = errorReportPackager,
        errorIncidentStore = errorIncidentStore,
        systemClipboardHelper = mockk(),
        workspaceRemote = mockk(),
    )

    private fun completedState(error: Throwable) = object : Operation.State.Completed {
        override val startedAt = Clock.System.now()
        override val completedAt = Clock.System.now()
        override val summary = "done".toCaString()
        override val report: Operation.Report? = null
        override val error: Throwable? = error
    }

    /** Chrome with a single failed operation, plus the collaborators the consent path drives. */
    private class ConsentFixture(
        val chrome: WorkspacePageChrome,
        val operationId: Operation.Id,
        val packager: ErrorReportPackager,
        val error: Throwable,
        val store: ErrorIncidentStore,
    ) {
        val incident get() = store.get(error)
    }

    private fun CoroutineScope.consentFixture(): ConsentFixture {
        val error = RuntimeException("boom")
        val state = completedState(error)
        val op = managedOp(MutableStateFlow(state))
        val operationsManager = mockk<OperationsManager>().apply {
            every { operations } returns MutableStateFlow(listOf(op))
            every { completedOperations } returns MutableSharedFlow<CompletedOperationSnapshot>(replay = 1).apply {
                tryEmit(CompletedOperationSnapshot(id = op.id, metadata = op.metadata, state = state))
            }
        }
        val packager = mockk<ErrorReportPackager>().apply {
            coEvery { packageReport(any(), any()) } returns PackagedErrorReport(
                uri = mockk(),
                payload = mockk(),
            )
        }
        val store = recordingIncidentStore()
        val chrome = chrome(
            operationsManager = operationsManager,
            errorReportTool = mockk<ErrorReportTool>().apply {
                every { createShareChooserIntent(any()) } returns mockk()
            },
            errorReportPackager = packager,
            errorIncidentStore = store,
        )
        return ConsentFixture(chrome, op.id, packager, error, store)
    }

    @Test
    fun `asking to share an operation error packages nothing yet`() = runTest {
        val fixture = backgroundScope.consentFixture()
        runCurrent()

        fixture.chrome.shareOperationError(fixture.operationId)
        runCurrent()

        fixture.chrome.pendingErrorShare.value?.incident shouldBe fixture.incident
        coVerify(exactly = 0) { fixture.packager.packageReport(any(), any()) }
    }

    /**
     * The incident the consent offers has to be the one frozen when the operation completed: an
     * incident minted at share time carries a log trail from minutes after the failure.
     */
    @Test
    fun `the shared incident is the one the completion was frozen into`() = runTest {
        val fixture = backgroundScope.consentFixture()
        runCurrent()

        fixture.chrome.shareOperationError(fixture.operationId)
        runCurrent()

        val shared = fixture.chrome.pendingErrorShare.value!!.incident
        (shared.error === fixture.error) shouldBe true
        shared.occurredAtIsApproximate shouldBe false
        shared.context.containsKey("incident.frozenAtShare") shouldBe false
    }

    @Test
    fun `declining the consent packages nothing`() = runTest {
        val fixture = backgroundScope.consentFixture()
        fixture.chrome.shareOperationError(fixture.operationId)
        runCurrent()

        fixture.chrome.dismissErrorShare()
        fixture.chrome.confirmErrorShare()
        runCurrent()

        fixture.chrome.pendingErrorShare.value shouldBe null
        coVerify(exactly = 0) { fixture.packager.packageReport(any(), any()) }
    }

    @Test
    fun `consenting packages the report exactly once`() = runTest {
        val fixture = backgroundScope.consentFixture()
        runCurrent()
        fixture.chrome.shareOperationError(fixture.operationId)
        runCurrent()
        val pending = fixture.chrome.pendingErrorShare.value!!.incident

        fixture.chrome.confirmErrorShare()
        runCurrent()

        coVerify(exactly = 1) { fixture.packager.packageReport(pending, any()) }
    }

    @Test
    fun `a double tap on the consent still packages only once`() = runTest {
        val fixture = backgroundScope.consentFixture()
        fixture.chrome.shareOperationError(fixture.operationId)
        runCurrent()

        fixture.chrome.confirmErrorShare()
        fixture.chrome.confirmErrorShare()
        runCurrent()

        coVerify(exactly = 1) { fixture.packager.packageReport(any(), any()) }
    }

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
            every { completedOperations } returns MutableSharedFlow()
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
            every { completedOperations } returns MutableSharedFlow()
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

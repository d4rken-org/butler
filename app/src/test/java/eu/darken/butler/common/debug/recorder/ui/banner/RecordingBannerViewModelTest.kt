package eu.darken.butler.common.debug.recorder.ui.banner

import eu.darken.butler.common.debug.bugreport.BugReportRecorder
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class RecordingBannerViewModelTest : BaseTest() {

    private lateinit var recorder: BugReportRecorder
    private lateinit var recorderStateFlow: MutableStateFlow<BugReportRecorder.State>
    private lateinit var workspaceRemote: WorkspaceRemote

    @BeforeEach
    fun setup() {
        recorderStateFlow = MutableStateFlow(BugReportRecorder.State())
        recorder = mockk(relaxed = true) {
            every { state } returns recorderStateFlow
            coEvery { requestStop() } returns BugReportRecorder.StopResult.NotRecording
        }
        workspaceRemote = mockk(relaxed = true) {
            coEvery { execute(any()) } returns WorkspaceAction.Create.Result.AlreadyOpen(Workspace.Id())
        }
    }

    private fun createViewModel(): RecordingBannerViewModel {
        return RecordingBannerViewModel(
            dispatcherProvider = TestDispatcherProvider(),
            bugReportRecorder = recorder,
            workspaceRemote = workspaceRemote,
        )
    }

    @Test
    fun `state maps isRecording correctly when not recording`() = runTest {
        recorderStateFlow.value = BugReportRecorder.State(isRecording = false)

        val state = createViewModel().state.first()

        state.isRecording shouldBe false
    }

    @Test
    fun `state maps recording fields correctly`() = runTest {
        val startedAt = System.currentTimeMillis()
        recorderStateFlow.value = BugReportRecorder.State(
            isRecording = true,
            recordingId = "recording_1_abcd",
            startedAtMs = startedAt,
            currentLogSize = 12345L,
        )

        val state = createViewModel().state.first()

        state.isRecording shouldBe true
        state.recordingStartedAt shouldBe startedAt
        state.currentLogSize shouldBe 12345L
    }

    @Test
    fun `state emits updated values when recorder state changes`() = runTest {
        val viewModel = createViewModel()

        recorderStateFlow.value = BugReportRecorder.State()
        viewModel.state.first().isRecording shouldBe false

        recorderStateFlow.value = BugReportRecorder.State(isRecording = true, currentLogSize = 100L)
        viewModel.state.first().let {
            it.isRecording shouldBe true
            it.currentLogSize shouldBe 100L
        }

        recorderStateFlow.value = recorderStateFlow.value.copy(currentLogSize = 200L)
        viewModel.state.first().currentLogSize shouldBe 200L
    }

    @Test
    fun `stopRecording delegates to recorder requestStop`() = runTest {
        createViewModel().stopRecording()

        coVerify { recorder.requestStop() }
    }

    @Test
    fun `openBugReports focuses workspace without stopping recording`() = runTest {
        recorderStateFlow.value = BugReportRecorder.State(isRecording = true, recordingId = "recording_1")

        createViewModel().openBugReports()

        coVerify { workspaceRemote.execute(any<WorkspaceAction.Create>()) }
        coVerify(exactly = 0) { recorder.requestStop() }
        coVerify(exactly = 0) { recorder.forceStop() }
    }
}

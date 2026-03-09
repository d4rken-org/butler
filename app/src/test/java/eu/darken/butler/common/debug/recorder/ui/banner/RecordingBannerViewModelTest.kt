package eu.darken.butler.common.debug.recorder.ui.banner

import eu.darken.butler.common.debug.recorder.core.DebugSessionManager
import eu.darken.butler.common.debug.recorder.core.RecorderManager
import io.kotest.matchers.shouldBe
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

    private lateinit var sessionManager: DebugSessionManager
    private lateinit var recorderStateFlow: MutableStateFlow<RecorderManager.State>

    @BeforeEach
    fun setup() {
        recorderStateFlow = MutableStateFlow(RecorderManager.State())
        sessionManager = mockk(relaxed = true) {
            every { recorderState } returns recorderStateFlow
        }
    }

    private fun createViewModel(): RecordingBannerViewModel {
        return RecordingBannerViewModel(
            dispatcherProvider = TestDispatcherProvider(),
            sessionManager = sessionManager,
        )
    }

    @Test
    fun `state maps isRecording correctly when not recording`() = runTest {
        recorderStateFlow.value = RecorderManager.State(
            shouldRecord = false,
            recorder = null,
        )

        val viewModel = createViewModel()
        val state = viewModel.state.first()

        state.isRecording shouldBe false
    }

    @Test
    fun `state maps isRecording correctly when recording`() = runTest {
        val mockRecorder = mockk<eu.darken.butler.common.debug.recorder.core.Recorder>()
        recorderStateFlow.value = RecorderManager.State(
            shouldRecord = true,
            recorder = mockRecorder,
        )

        val viewModel = createViewModel()
        val state = viewModel.state.first()

        state.isRecording shouldBe true
    }

    @Test
    fun `state maps recordingStartedAt correctly`() = runTest {
        val startedAt = System.currentTimeMillis()
        val mockRecorder = mockk<eu.darken.butler.common.debug.recorder.core.Recorder>()
        recorderStateFlow.value = RecorderManager.State(
            shouldRecord = true,
            recorder = mockRecorder,
            recordingStartedAt = startedAt,
        )

        val viewModel = createViewModel()
        val state = viewModel.state.first()

        state.recordingStartedAt shouldBe startedAt
    }

    @Test
    fun `state maps currentLogSize correctly`() = runTest {
        val mockRecorder = mockk<eu.darken.butler.common.debug.recorder.core.Recorder>()
        recorderStateFlow.value = RecorderManager.State(
            shouldRecord = true,
            recorder = mockRecorder,
            currentLogSize = 12345L,
        )

        val viewModel = createViewModel()
        val state = viewModel.state.first()

        state.currentLogSize shouldBe 12345L
    }

    @Test
    fun `state emits updated values when RecorderManager state changes`() = runTest {
        val viewModel = createViewModel()

        // Initial state - not recording
        recorderStateFlow.value = RecorderManager.State()
        var state = viewModel.state.first()
        state.isRecording shouldBe false

        // Start recording
        val mockRecorder = mockk<eu.darken.butler.common.debug.recorder.core.Recorder>()
        recorderStateFlow.value = RecorderManager.State(
            shouldRecord = true,
            recorder = mockRecorder,
            currentLogSize = 100L,
        )
        state = viewModel.state.first()
        state.isRecording shouldBe true
        state.currentLogSize shouldBe 100L

        // Update log size
        recorderStateFlow.value = recorderStateFlow.value.copy(currentLogSize = 200L)
        state = viewModel.state.first()
        state.currentLogSize shouldBe 200L
    }

    @Test
    fun `stopRecording calls sessionManager requestStopRecording`() = runTest {
        val viewModel = createViewModel()

        viewModel.stopRecording()

        coVerify { sessionManager.requestStopRecording() }
    }
}

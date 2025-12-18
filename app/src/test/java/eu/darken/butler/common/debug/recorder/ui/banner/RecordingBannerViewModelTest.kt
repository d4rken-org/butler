package eu.darken.butler.common.debug.recorder.ui.banner

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
import kotlin.time.Clock

class RecordingBannerViewModelTest : BaseTest() {

    private lateinit var recorderManager: RecorderManager
    private lateinit var managerStateFlow: MutableStateFlow<RecorderManager.State>

    @BeforeEach
    fun setup() {
        managerStateFlow = MutableStateFlow(RecorderManager.State())
        recorderManager = mockk(relaxed = true) {
            every { state } returns managerStateFlow
        }
    }

    private fun createViewModel(): RecordingBannerViewModel {
        return RecordingBannerViewModel(
            dispatcherProvider = TestDispatcherProvider(),
            recorderManager = recorderManager,
        )
    }

    @Test
    fun `state maps isRecording correctly when not recording`() = runTest {
        managerStateFlow.value = RecorderManager.State(
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
        managerStateFlow.value = RecorderManager.State(
            shouldRecord = true,
            recorder = mockRecorder,
        )

        val viewModel = createViewModel()
        val state = viewModel.state.first()

        state.isRecording shouldBe true
    }

    @Test
    fun `state maps recordingStartTime correctly`() = runTest {
        val startTime = Clock.System.now()
        val mockRecorder = mockk<eu.darken.butler.common.debug.recorder.core.Recorder>()
        managerStateFlow.value = RecorderManager.State(
            shouldRecord = true,
            recorder = mockRecorder,
            recordingStartTime = startTime,
        )

        val viewModel = createViewModel()
        val state = viewModel.state.first()

        state.recordingStartTime shouldBe startTime
    }

    @Test
    fun `state maps currentLogSize correctly`() = runTest {
        val mockRecorder = mockk<eu.darken.butler.common.debug.recorder.core.Recorder>()
        managerStateFlow.value = RecorderManager.State(
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
        managerStateFlow.value = RecorderManager.State()
        var state = viewModel.state.first()
        state.isRecording shouldBe false

        // Start recording
        val mockRecorder = mockk<eu.darken.butler.common.debug.recorder.core.Recorder>()
        managerStateFlow.value = RecorderManager.State(
            shouldRecord = true,
            recorder = mockRecorder,
            currentLogSize = 100L,
        )
        state = viewModel.state.first()
        state.isRecording shouldBe true
        state.currentLogSize shouldBe 100L

        // Update log size
        managerStateFlow.value = managerStateFlow.value.copy(currentLogSize = 200L)
        state = viewModel.state.first()
        state.currentLogSize shouldBe 200L
    }

    @Test
    fun `stopRecording calls recorderManager stopRecorder`() = runTest {
        val viewModel = createViewModel()

        viewModel.stopRecording()

        coVerify { recorderManager.stopRecorder() }
    }
}

package eu.darken.butler.common.debug.recorder.core

import android.content.Context
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.main.core.CurriculumVitae
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File

class RecorderManagerTest : BaseTest() {

    private lateinit var context: Context
    private lateinit var butlerId: ButlerId
    private lateinit var debugSettings: DebugSettings
    private lateinit var curriculumVitae: CurriculumVitae

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        butlerId = mockk {
            every { id } returns "test-butler-id"
        }
        debugSettings = mockk(relaxed = true)
        curriculumVitae = mockk {
            every { history } returns emptyFlow()
        }
    }

    @Nested
    inner class StateDataClass {

        @Test
        fun `isRecording returns false when recorder is null`() {
            val state = RecorderManager.State(
                shouldRecord = true,
                recorder = null,
            )

            state.isRecording shouldBe false
        }

        @Test
        fun `isRecording returns true when recorder is present`() {
            val mockRecorder = mockk<Recorder>()
            val state = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
            )

            state.isRecording shouldBe true
        }

        @Test
        fun `default state has expected values`() {
            val state = RecorderManager.State()

            state.shouldRecord shouldBe false
            state.recorder.shouldBeNull()
            state.currentLogDir.shouldBeNull()
            state.recordingStartedAt shouldBe 0L
            state.currentLogSize shouldBe 0L
            state.isRecording shouldBe false
        }

        @Test
        fun `state preserves all properties`(@TempDir tempDir: File) {
            val mockRecorder = mockk<Recorder>()
            val currentDir = File(tempDir, "current")
            val startedAt = System.currentTimeMillis()

            val state = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                currentLogDir = currentDir,
                recordingStartedAt = startedAt,
                currentLogSize = 12345L,
            )

            state.shouldRecord shouldBe true
            state.recorder shouldBe mockRecorder
            state.currentLogDir shouldBe currentDir
            state.recordingStartedAt shouldBe startedAt
            state.currentLogSize shouldBe 12345L
        }
    }

    @Nested
    inner class StateTransitions {

        @Test
        fun `state copy with shouldRecord change preserves other fields`(@TempDir tempDir: File) {
            val mockRecorder = mockk<Recorder>()
            val logDir = File(tempDir, "logs")
            val startedAt = System.currentTimeMillis()

            val state = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                currentLogDir = logDir,
                recordingStartedAt = startedAt,
                currentLogSize = 1000L,
            )

            val newState = state.copy(shouldRecord = false)

            newState.shouldRecord shouldBe false
            newState.recorder shouldBe mockRecorder
            newState.currentLogDir shouldBe logDir
            newState.recordingStartedAt shouldBe startedAt
            newState.currentLogSize shouldBe 1000L
        }

        @Test
        fun `state can track transition from not recording to recording`(@TempDir tempDir: File) {
            val initialState = RecorderManager.State(
                shouldRecord = false,
                recorder = null,
            )

            initialState.isRecording shouldBe false

            val mockRecorder = mockk<Recorder>()
            val logDir = File(tempDir, "logs")
            val recordingState = initialState.copy(
                shouldRecord = true,
                recorder = mockRecorder,
                currentLogDir = logDir,
                recordingStartedAt = System.currentTimeMillis(),
            )

            recordingState.isRecording shouldBe true
            recordingState.currentLogDir shouldBe logDir
        }

        @Test
        fun `state can track transition from recording to stopped`(@TempDir tempDir: File) {
            val mockRecorder = mockk<Recorder>()
            val logDir = File(tempDir, "logs")

            val recordingState = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                currentLogDir = logDir,
                recordingStartedAt = System.currentTimeMillis(),
                currentLogSize = 5000L,
            )

            recordingState.isRecording shouldBe true

            val stoppedState = recordingState.copy(
                shouldRecord = false,
                recorder = null,
                currentLogDir = null,
                recordingStartedAt = 0L,
                currentLogSize = 0L,
            )

            stoppedState.isRecording shouldBe false
            stoppedState.currentLogDir.shouldBeNull()
        }
    }

    @Nested
    inner class LogSizeTracking {

        @Test
        fun `state tracks log size updates`() {
            val mockRecorder = mockk<Recorder>()
            var state = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                currentLogSize = 0L,
            )

            state.currentLogSize shouldBe 0L

            state = state.copy(currentLogSize = 1000L)
            state.currentLogSize shouldBe 1000L

            state = state.copy(currentLogSize = 2500L)
            state.currentLogSize shouldBe 2500L
        }
    }

    @Nested
    inner class RecordingStartedAt {

        @Test
        fun `state tracks recording started at`() {
            val mockRecorder = mockk<Recorder>()
            val startedAt = System.currentTimeMillis()

            val state = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                recordingStartedAt = startedAt,
            )

            state.recordingStartedAt shouldBe startedAt
        }

        @Test
        fun `state clears recording started at when stopped`() {
            val mockRecorder = mockk<Recorder>()
            val startedAt = System.currentTimeMillis()

            val recordingState = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                recordingStartedAt = startedAt,
            )

            val stoppedState = recordingState.copy(
                recorder = null,
                recordingStartedAt = 0L,
            )

            stoppedState.recordingStartedAt shouldBe 0L
        }
    }
}

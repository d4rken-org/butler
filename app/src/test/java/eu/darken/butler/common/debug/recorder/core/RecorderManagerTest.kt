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
import kotlin.time.Clock

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
            state.lastLogDir.shouldBeNull()
            state.recordingStartTime.shouldBeNull()
            state.currentLogSize shouldBe 0L
            state.isRecording shouldBe false
        }

        @Test
        fun `state preserves all properties`(@TempDir tempDir: File) {
            val mockRecorder = mockk<Recorder>()
            val currentDir = File(tempDir, "current")
            val lastDir = File(tempDir, "last")
            val startTime = Clock.System.now()

            val state = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                currentLogDir = currentDir,
                lastLogDir = lastDir,
                recordingStartTime = startTime,
                currentLogSize = 12345L,
            )

            state.shouldRecord shouldBe true
            state.recorder shouldBe mockRecorder
            state.currentLogDir shouldBe currentDir
            state.lastLogDir shouldBe lastDir
            state.recordingStartTime shouldBe startTime
            state.currentLogSize shouldBe 12345L
        }
    }

    @Nested
    inner class StateTransitions {

        @Test
        fun `state copy with shouldRecord change preserves other fields`(@TempDir tempDir: File) {
            val mockRecorder = mockk<Recorder>()
            val logDir = File(tempDir, "logs")
            val startTime = Clock.System.now()

            val state = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                currentLogDir = logDir,
                recordingStartTime = startTime,
                currentLogSize = 1000L,
            )

            val newState = state.copy(shouldRecord = false)

            newState.shouldRecord shouldBe false
            newState.recorder shouldBe mockRecorder
            newState.currentLogDir shouldBe logDir
            newState.recordingStartTime shouldBe startTime
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
                recordingStartTime = Clock.System.now(),
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
                recordingStartTime = Clock.System.now(),
                currentLogSize = 5000L,
            )

            recordingState.isRecording shouldBe true

            val stoppedState = recordingState.copy(
                shouldRecord = false,
                recorder = null,
                lastLogDir = logDir,
                currentLogDir = null,
                recordingStartTime = null,
                currentLogSize = 0L,
            )

            stoppedState.isRecording shouldBe false
            stoppedState.lastLogDir shouldBe logDir
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
    inner class RecordingStartTime {

        @Test
        fun `state tracks recording start time`() {
            val mockRecorder = mockk<Recorder>()
            val startTime = Clock.System.now()

            val state = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                recordingStartTime = startTime,
            )

            state.recordingStartTime shouldBe startTime
        }

        @Test
        fun `state clears recording start time when stopped`() {
            val mockRecorder = mockk<Recorder>()
            val startTime = Clock.System.now()

            val recordingState = RecorderManager.State(
                shouldRecord = true,
                recorder = mockRecorder,
                recordingStartTime = startTime,
            )

            val stoppedState = recordingState.copy(
                recorder = null,
                recordingStartTime = null,
            )

            stoppedState.recordingStartTime.shouldBeNull()
        }
    }
}

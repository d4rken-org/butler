package eu.darken.butler.common.debug.recorder.ui.result

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.debug.recorder.core.DebugSession
import eu.darken.butler.common.debug.recorder.core.DebugSessionManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import kotlin.time.Instant

class RecorderViewModelTest : BaseTest() {

    private lateinit var context: Context
    private lateinit var webpageTool: WebpageTool
    private lateinit var sessionManager: DebugSessionManager
    private lateinit var sessionsFlow: MutableStateFlow<List<DebugSession>>

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        webpageTool = mockk(relaxed = true)
        sessionsFlow = MutableStateFlow(emptyList())
        sessionManager = mockk(relaxed = true) {
            every { sessions } returns sessionsFlow
        }
    }

    private fun createSavedStateHandle(
        sessionId: String? = null,
        legacyPath: String? = null,
    ): SavedStateHandle {
        return SavedStateHandle().apply {
            if (sessionId != null) set(RecorderActivity.RECORD_SESSION_ID, sessionId)
            if (legacyPath != null) set(RecorderActivity.RECORD_PATH, legacyPath)
        }
    }

    private fun createViewModel(
        sessionId: String? = null,
        legacyPath: String? = null,
    ): RecorderViewModel {
        return RecorderViewModel(
            dispatchers = TestDispatcherProvider(),
            handle = createSavedStateHandle(sessionId, legacyPath),
            context = context,
            sessionManager = sessionManager,
            webpageTool = webpageTool,
        )
    }

    @Nested
    inner class StateDataClass {

        @Test
        fun `default state has expected values`() {
            val state = RecorderViewModel.State()

            state.logDir shouldBe null
            state.logEntries shouldBe emptyList()
            state.totalSize shouldBe 0L
            state.compressedSize shouldBe -1L
            state.recordingDurationSecs shouldBe 0L
            state.isWorking shouldBe true
        }

        @Test
        fun `state preserves compressedSize`(@TempDir tempDir: File) {
            val state = RecorderViewModel.State(
                logDir = tempDir,
                compressedSize = 1024L,
            )

            state.compressedSize shouldBe 1024L
        }
    }

    @Nested
    inner class LogEntryDataClass {

        @Test
        fun `LogEntry stores file and size`(@TempDir tempDir: File) {
            val file = File(tempDir, "test.log")
            val entry = RecorderViewModel.LogEntry(
                file = file,
                size = 2048L,
            )

            entry.file shouldBe file
            entry.size shouldBe 2048L
        }
    }

    @Nested
    inner class GoPrivacyPolicy {

        @Test
        fun `goPrivacyPolicy opens privacy policy link`() {
            val viewModel = createViewModel(legacyPath = "/tmp/logs")

            viewModel.goPrivacyPolicy()

            verify { webpageTool.open(ButlerLinks.PRIVACY_POLICY) }
        }
    }

    @Nested
    inner class Discard {

        @Test
        fun `discard calls sessionManager deleteSession and emits Finish`() = runTest {
            val sessionId = "cache:test_session"
            coEvery { sessionManager.deleteSession(sessionId) } returns Unit

            val viewModel = createViewModel(sessionId = sessionId)

            viewModel.discard()

            coVerify { sessionManager.deleteSession(sessionId) }
        }
    }

    @Nested
    inner class Keep {

        @Test
        fun `keep emits Finish event`() = runTest {
            val viewModel = createViewModel(sessionId = "cache:test")

            viewModel.keep()

            viewModel.events.first() shouldBe RecorderViewModel.Event.Finish
        }
    }

    @Nested
    inner class Initialization {

        @Test
        fun `initial state resolves session from sessionId`(@TempDir tempDir: File) = runTest {
            val logDir = File(tempDir, "logs").apply { mkdirs() }
            File(logDir, "test.log").writeText("test content")

            val sessionId = "cache:logs"
            val readySession = DebugSession.Ready(
                id = sessionId,
                displayName = "logs",
                createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                diskSize = 100L,
                logDir = logDir,
                zipFile = null,
                compressedSize = 0L,
            )
            sessionsFlow.value = listOf(readySession)

            val viewModel = createViewModel(sessionId = sessionId)

            val state = viewModel.state.first()
            state.logDir shouldBe logDir
        }

        @Test
        fun `init lists log files`(@TempDir tempDir: File) = runTest {
            val logDir = File(tempDir, "logs").apply { mkdirs() }
            File(logDir, "file1.log").writeText("content 1")
            File(logDir, "file2.log").writeText("content 2 longer")

            val sessionId = "cache:logs"
            sessionsFlow.value = listOf(
                DebugSession.Ready(
                    id = sessionId,
                    displayName = "logs",
                    createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    diskSize = 100L,
                    logDir = logDir,
                    zipFile = null,
                    compressedSize = 0L,
                )
            )

            val viewModel = createViewModel(sessionId = sessionId)

            val state = viewModel.state.first()
            state.logEntries.size shouldBe 2
        }
    }
}

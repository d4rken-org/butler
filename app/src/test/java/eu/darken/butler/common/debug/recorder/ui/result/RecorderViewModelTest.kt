package eu.darken.butler.common.debug.recorder.ui.result

import android.content.Context
import android.text.format.Formatter
import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.WebpageTool
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File

class RecorderViewModelTest : BaseTest() {

    private lateinit var context: Context
    private lateinit var webpageTool: WebpageTool

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        webpageTool = mockk(relaxed = true)

        mockkStatic(Formatter::class)
        every { Formatter.formatShortFileSize(any(), any()) } answers {
            val bytes = secondArg<Long>()
            "${bytes / 1024} KB"
        }
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Formatter::class)
    }

    private fun createSavedStateHandle(path: String? = null): SavedStateHandle {
        return SavedStateHandle().apply {
            if (path != null) {
                set(RecorderActivity.RECORD_PATH, path)
            }
        }
    }

    @Nested
    inner class StateDataClass {

        @Test
        fun `getFormattedCompressedSize returns formatted size when available`(@TempDir tempDir: File) {
            val state = RecorderViewModel.State(
                logDir = tempDir,
                compressedSize = 1024L,
            )

            val result = state.getFormattedCompressedSize(context)

            result shouldBe "1 KB"
        }

        @Test
        fun `getFormattedCompressedSize returns null when size not available`(@TempDir tempDir: File) {
            val state = RecorderViewModel.State(
                logDir = tempDir,
                compressedSize = null,
            )

            val result = state.getFormattedCompressedSize(context)

            result.shouldBeNull()
        }
    }

    @Nested
    inner class LogFileItemDataClass {

        @Test
        fun `getFormattedSize returns formatted size when available`(@TempDir tempDir: File) {
            val item = RecorderViewModel.LogFileItem(
                path = File(tempDir, "test.log"),
                size = 2048L,
            )

            val result = item.getFormattedSize(context)

            result shouldBe "2 KB"
        }

        @Test
        fun `getFormattedSize returns null when size not available`(@TempDir tempDir: File) {
            val item = RecorderViewModel.LogFileItem(
                path = File(tempDir, "test.log"),
                size = null,
            )

            val result = item.getFormattedSize(context)

            result.shouldBeNull()
        }
    }

    @Nested
    inner class GoPrivacyPolicy {

        @Test
        fun `goPrivacyPolicy opens privacy policy link`(@TempDir tempDir: File) = runTest {
            val logDir = File(tempDir, "logs").apply { mkdirs() }
            File(logDir, "test.log").writeText("test content")

            val viewModel = RecorderViewModel(
                dispatchers = TestDispatcherProvider(),
                handle = createSavedStateHandle(logDir.path),
                context = context,
                webpageTool = webpageTool,
            )

            viewModel.goPrivacyPolicy()

            verify { webpageTool.open(ButlerLinks.PRIVACY_POLICY) }
        }
    }

    @Nested
    inner class Discard {

        @Test
        fun `discard emits closeEvent`(@TempDir tempDir: File) = runTest {
            val logDir = File(tempDir, "logs").apply { mkdirs() }
            File(logDir, "test.log").writeText("test content")

            val viewModel = RecorderViewModel(
                dispatchers = TestDispatcherProvider(),
                handle = createSavedStateHandle(logDir.path),
                context = context,
                webpageTool = webpageTool,
            )

            viewModel.discard()

            viewModel.closeEvent.first() shouldBe Unit
        }
    }

    @Nested
    inner class Initialization {

        @Test
        fun `initial state has logDir set`(@TempDir tempDir: File) = runTest {
            val logDir = File(tempDir, "logs").apply { mkdirs() }
            File(logDir, "test.log").writeText("test content")

            val viewModel = RecorderViewModel(
                dispatchers = TestDispatcherProvider(),
                handle = createSavedStateHandle(logDir.path),
                context = context,
                webpageTool = webpageTool,
            )

            val state = viewModel.state.first()

            state.logDir shouldBe logDir
        }

        @Test
        fun `init lists log files`(@TempDir tempDir: File) = runTest {
            val logDir = File(tempDir, "logs").apply { mkdirs() }
            File(logDir, "file1.log").writeText("content 1")
            File(logDir, "file2.log").writeText("content 2 longer")

            val viewModel = RecorderViewModel(
                dispatchers = TestDispatcherProvider(),
                handle = createSavedStateHandle(logDir.path),
                context = context,
                webpageTool = webpageTool,
            )

            val state = viewModel.state.first { !it.isWorking }

            state.logEntries.size shouldBe 2
        }

        @Test
        fun `init sorts files by size descending`(@TempDir tempDir: File) = runTest {
            val logDir = File(tempDir, "logs").apply { mkdirs() }
            File(logDir, "small.log").writeText("x")
            File(logDir, "large.log").writeText("x".repeat(1000))

            val viewModel = RecorderViewModel(
                dispatchers = TestDispatcherProvider(),
                handle = createSavedStateHandle(logDir.path),
                context = context,
                webpageTool = webpageTool,
            )

            val state = viewModel.state.first { !it.isWorking }

            state.logEntries[0].path.name shouldBe "large.log"
            state.logEntries[1].path.name shouldBe "small.log"
        }

        @Test
        fun `init creates compressed file`(@TempDir tempDir: File) = runTest {
            val logDir = File(tempDir, "logs").apply { mkdirs() }
            File(logDir, "test.log").writeText("test content")

            val viewModel = RecorderViewModel(
                dispatchers = TestDispatcherProvider(),
                handle = createSavedStateHandle(logDir.path),
                context = context,
                webpageTool = webpageTool,
            )

            val state = viewModel.state.first { !it.isWorking }

            state.compressedFile shouldBe File(tempDir, "logs.zip")
            state.compressedFile!!.exists() shouldBe true
        }

        @Test
        fun `init sets isWorking to false after completion`(@TempDir tempDir: File) = runTest {
            val logDir = File(tempDir, "logs").apply { mkdirs() }
            File(logDir, "test.log").writeText("test content")

            val viewModel = RecorderViewModel(
                dispatchers = TestDispatcherProvider(),
                handle = createSavedStateHandle(logDir.path),
                context = context,
                webpageTool = webpageTool,
            )

            val state = viewModel.state.first { !it.isWorking }

            state.isWorking shouldBe false
        }
    }
}

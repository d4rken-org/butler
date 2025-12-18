package eu.darken.butler.common.debug.recorder.core

import eu.darken.butler.common.debug.logging.FileLogger
import eu.darken.butler.common.debug.logging.Logging
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File

class RecorderTest : BaseTest() {

    @BeforeEach
    fun setup() {
        mockkObject(Logging)
        every { Logging.install(any()) } just Runs
        every { Logging.remove(any()) } just Runs

        mockkConstructor(FileLogger::class)
        every { anyConstructed<FileLogger>().start() } just Runs
        every { anyConstructed<FileLogger>().stop() } just Runs
    }

    @AfterEach
    fun teardown() {
        unmockkConstructor(FileLogger::class)
    }

    @Test
    fun `start creates FileLogger with correct path`(@TempDir tempDir: File) = runTest {
        val recorder = Recorder()

        recorder.start(tempDir)

        verify { anyConstructed<FileLogger>().start() }
    }

    @Test
    fun `start installs FileLogger to Logging system`(@TempDir tempDir: File) = runTest {
        val recorder = Recorder()

        recorder.start(tempDir)

        verify { Logging.install(any<FileLogger>()) }
    }

    @Test
    fun `start is idempotent - calling twice only creates one FileLogger`(@TempDir tempDir: File) = runTest {
        val recorder = Recorder()

        recorder.start(tempDir)
        recorder.start(tempDir)

        verify(exactly = 1) { anyConstructed<FileLogger>().start() }
        verify(exactly = 1) { Logging.install(any<FileLogger>()) }
    }

    @Test
    fun `stop removes FileLogger from Logging system`(@TempDir tempDir: File) = runTest {
        val recorder = Recorder()
        recorder.start(tempDir)

        recorder.stop()

        verify { Logging.remove(any<FileLogger>()) }
    }

    @Test
    fun `stop closes FileLogger`(@TempDir tempDir: File) = runTest {
        val recorder = Recorder()
        recorder.start(tempDir)

        recorder.stop()

        verify { anyConstructed<FileLogger>().stop() }
    }

    @Test
    fun `stop is idempotent - calling twice only stops once`(@TempDir tempDir: File) = runTest {
        val recorder = Recorder()
        recorder.start(tempDir)

        recorder.stop()
        recorder.stop()

        verify(exactly = 1) { anyConstructed<FileLogger>().stop() }
        verify(exactly = 1) { Logging.remove(any<FileLogger>()) }
    }

    @Test
    fun `stop without start does nothing`() = runTest {
        val recorder = Recorder()

        recorder.stop()

        verify(exactly = 0) { Logging.remove(any()) }
    }
}

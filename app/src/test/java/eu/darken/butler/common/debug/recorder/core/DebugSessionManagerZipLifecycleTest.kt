package eu.darken.butler.common.debug.recorder.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.io.IOException

class DebugSessionManagerZipLifecycleTest : BaseTest() {

    @TempDir lateinit var testDir: File
    private lateinit var logParent: File
    private lateinit var recorderState: MutableStateFlow<RecorderManager.State>
    private lateinit var recorderManager: RecorderManager
    private lateinit var debugLogZipper: DebugLogZipper

    @BeforeEach
    fun setup() {
        logParent = File(testDir, "logs").apply { mkdirs() }
        recorderState = MutableStateFlow(RecorderManager.State())
        recorderManager = mockk<RecorderManager>().apply {
            every { state } returns recorderState
            every { getLogDirectories() } returns listOf(logParent)
            every { currentLogDir } returns null
        }
        debugLogZipper = mockk()
    }

    private fun TestScope.createManager() = DebugSessionManager(
        appScope = backgroundScope,
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        recorderManager = recorderManager,
        debugLogZipper = debugLogZipper,
    )

    private fun createSessionDir(name: String, coreLogContent: String = "log content"): File {
        val dir = File(logParent, name).apply { mkdirs() }
        File(dir, "core.log").writeText(coreLogContent)
        return dir
    }

    private fun createZipFile(name: String, size: Int = 100): File =
        File(logParent, "$name.zip").apply { writeBytes(ByteArray(size)) }

    /** Mock that actually creates the zip file, preventing repeated orphan auto-zips */
    private fun mockZipperCreatesFile() {
        every { debugLogZipper.zip(any()) } answers {
            val logDir = firstArg<File>()
            File(logDir.parentFile, "${logDir.name}.zip").apply { writeBytes(ByteArray(50)) }
        }
    }

    private fun sessionId(name: String): String = "ext:$name"

    @Nested
    inner class DuplicateClaims {

        @Test
        fun `duplicate zip requests for the same session are skipped`() = runTest {
            // A stale scan's orphan auto-zip racing the stop-path zip used to launch a second
            // zip job that released the shared zippingIds entry while the first was running.
            val sessionDir = createSessionDir("session1")
            mockZipperCreatesFile()
            coEvery { recorderManager.requestStopRecorder() } returns RecorderManager.StopResult.Stopped(
                logDir = sessionDir,
                sessionId = sessionId("session1"),
            )

            val manager = createManager()
            manager.requestStopRecording()
            manager.requestStopRecording()

            // Suspend until the zip completes — background jobs only run while the test body
            // is suspended. This also exercises the live zippingIds read in orphan detection:
            // the scan runs while the claimed zip is in flight and must not schedule another.
            manager.sessions.first { sessions ->
                sessions.filterIsInstance<DebugSession.Ready>().any { it.zipFile != null }
            }

            verify(exactly = 1) { debugLogZipper.zip(any()) }
        }

        @Test
        fun `redundant zip request for an already-zipped session does no work`() = runTest {
            // A scan that straddles a completed zip can request a re-zip of a session whose
            // valid zip already exists — the disk re-check inside the zip job must skip it.
            val sessionDir = createSessionDir("session1")
            val zipFile = createZipFile("session1")
            zipFile.setLastModified(sessionDir.lastModified() + 10_000)
            coEvery { recorderManager.requestStopRecorder() } returns RecorderManager.StopResult.Stopped(
                logDir = sessionDir,
                sessionId = sessionId("session1"),
            )

            val manager = createManager()
            manager.requestStopRecording()

            // The claim is registered synchronously, so the first emission shows Compressing;
            // Ready only re-appears after the zip job has run and released its claim.
            manager.sessions.first { sessions ->
                sessions.filterIsInstance<DebugSession.Ready>().any { it.zipFile != null }
            }

            verify(exactly = 0) { debugLogZipper.zip(any()) }
        }
    }

    @Nested
    inner class ManualZip {

        @Test
        fun `zipSession returns an existing valid zip without re-zipping`() = runTest {
            val sessionDir = createSessionDir("session1")
            val zipFile = createZipFile("session1")
            zipFile.setLastModified(sessionDir.lastModified() + 10_000)

            val manager = createManager()
            val result = manager.zipSession(sessionId("session1"))

            result shouldBe zipFile
            verify(exactly = 0) { debugLogZipper.zip(any()) }
        }
    }

    @Nested
    inner class FailureLifecycle {

        @Test
        fun `failed auto-zip is not retried on subsequent scans`() = runTest {
            createSessionDir("session1")
            every { debugLogZipper.zip(any()) } throws IOException("zip failed")

            val manager = createManager()

            // First scan auto-zips the orphan, the zip fails, the failure-triggered rescan
            // must NOT re-attempt it.
            val sessions = manager.sessions.first { sessions -> sessions.any { it is DebugSession.Failed } }
            sessions shouldHaveSize 1

            manager.refresh()
            manager.sessions.first()
            testScheduler.advanceUntilIdle()

            verify(exactly = 1) { debugLogZipper.zip(any()) }
        }

        @Test
        fun `zipSession failure marks the session failed and prevents auto-retry`() = runTest {
            createSessionDir("session1")
            every { debugLogZipper.zip(any()) } throws IOException("zip failed")

            val manager = createManager()

            shouldThrow<IOException> {
                manager.zipSession(sessionId("session1"))
            }

            // The orphan dir is excluded from auto-zip by the failure marker and shown as Failed.
            val sessions = manager.sessions.first()
            sessions shouldHaveSize 1
            val session = sessions.first()
            session.shouldBeInstanceOf<DebugSession.Failed>()
            (session as DebugSession.Failed).reason shouldBe DebugSession.Failed.Reason.ZIP_FAILED

            testScheduler.advanceUntilIdle()
            verify(exactly = 1) { debugLogZipper.zip(any()) }
        }

        @Test
        fun `successful manual retry clears the failure marker`() = runTest {
            createSessionDir("session1")
            every { debugLogZipper.zip(any()) } throws IOException("zip failed")

            val manager = createManager()

            shouldThrow<IOException> { manager.zipSession(sessionId("session1")) }

            mockZipperCreatesFile()
            val zip = manager.zipSession(sessionId("session1"))
            zip.exists() shouldBe true

            val sessions = manager.sessions.first()
            sessions shouldHaveSize 1
            sessions.first().shouldBeInstanceOf<DebugSession.Ready>()
        }

        @Test
        fun `valid zip on disk wins over a stale failure overlay`() = runTest {
            createSessionDir("session1")
            every { debugLogZipper.zip(any()) } throws IOException("zip failed")

            val manager = createManager()

            shouldThrow<IOException> { manager.zipSession(sessionId("session1")) }
            manager.sessions.first().first().shouldBeInstanceOf<DebugSession.Failed>()

            // A valid zip appears on disk while the in-memory failure marker is still set —
            // the scan result must present the session as Ready.
            createZipFile("session1")
            manager.refresh()
            val sessions = manager.sessions.first { sessions -> sessions.any { it is DebugSession.Ready } }
            sessions shouldHaveSize 1
        }
    }

    @Nested
    inner class DeleteSession {

        @Test
        fun `deleteSession removes a stale zip temp file`() = runTest {
            val sessionDir = createSessionDir("session1")
            val zipFile = createZipFile("session1")
            val tmpFile = File(logParent, "session1.zip.tmp").apply { writeBytes(ByteArray(10)) }

            val manager = createManager()
            manager.deleteSession(sessionId("session1"))

            sessionDir.exists() shouldBe false
            zipFile.exists() shouldBe false
            tmpFile.exists() shouldBe false
        }
    }
}

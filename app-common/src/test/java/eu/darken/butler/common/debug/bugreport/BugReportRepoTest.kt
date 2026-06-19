package eu.darken.butler.common.debug.bugreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.debug.logging.RingLogBuffer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugReportRepoTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val reportsDir get() = File(context.filesDir, "bugreports")
    private val json = Json { ignoreUnknownKeys = true }
    private val recorderState = MutableStateFlow(BugReportRecorder.State())

    private fun createRepo(): BugReportRepo {
        val buffer = RingLogBuffer().apply {
            log(eu.darken.butler.common.debug.logging.Logging.Priority.INFO, "Test", "log line", null)
        }
        val recorder = mockk<BugReportRecorder>(relaxed = true) {
            every { state } returns recorderState
        }
        return BugReportRepo(
            context = context,
            appScope = CoroutineScope(Dispatchers.Unconfined),
            dispatcherProvider = TestDispatcherProvider(),
            ringLogBuffer = buffer,
            bugReportRecorder = recorder,
            butlerId = ButlerId(context),
            json = json,
        )
    }

    private fun writeReportDir(id: String, type: BugReport.Type, ongoing: Boolean) {
        val dir = File(reportsDir, id).apply { mkdirs() }
        val report = BugReport(
            id = id,
            createdAt = kotlin.time.Clock.System.now(),
            type = type,
            appVersion = "1.0",
            deviceFingerprint = "fp",
            apiLevel = "29",
            flavor = "FOSS",
            buildType = "DEBUG",
            installId = "iid",
            locale = "en",
        )
        File(dir, "meta.json").writeText(json.encodeToString(BugReport.serializer(), report))
        File(dir, "report.log").writeText("rec log")
        if (ongoing) File(dir, ".recording").createNewFile()
    }

    @Test
    fun `captureCrashBlocking stores a crash report with log`() = runTest {
        val repo = createRepo()
        repo.captureCrashBlocking(IllegalStateException("boom"), Thread.currentThread())

        val reports = repo.reports.first()
        reports shouldHaveSize 1
        val info = reports.single()
        info.report.type shouldBe BugReport.Type.CRASH
        info.report.errorClass shouldBe "java.lang.IllegalStateException"
        info.report.errorMessage shouldBe "boom"
        info.isSeen shouldBe false

        repo.readLog(info.id) shouldContain "log line"
        repo.hasUnseenCrashes.first() shouldBe true
    }

    @Test
    fun `markSeen clears the unseen flag`() = runTest {
        val repo = createRepo()
        repo.captureCrashBlocking(IllegalStateException("boom"), Thread.currentThread())
        val id = repo.reports.first().single().id

        repo.markSeen(id)

        repo.reports.first().single().isSeen shouldBe true
        repo.hasUnseenCrashes.first() shouldBe false
    }

    @Test
    fun `delete removes a report`() = runTest {
        val repo = createRepo()
        repo.captureCrashBlocking(IllegalStateException("boom"), Thread.currentThread())
        val id = repo.reports.first().single().id

        repo.delete(id)

        repo.reports.first() shouldHaveSize 0
    }

    @Test
    fun `scan skips incomplete and corrupt directories`() = runTest {
        val repo = createRepo()
        repo.captureCrashBlocking(IllegalStateException("boom"), Thread.currentThread())

        File(reportsDir, "incomplete").mkdirs()
        File(reportsDir, ".tmp-partial").mkdirs()
        File(reportsDir, "corrupt").mkdirs()
        File(File(reportsDir, "corrupt"), "meta.json").writeText("{ not valid json")

        repo.reports.first() shouldHaveSize 1
    }

    @Test
    fun `retention keeps only the newest reports`() = runTest {
        val repo = createRepo()
        repeat(30) { repo.captureReport(IllegalStateException("e$it")) }

        repo.reports.first() shouldHaveSize 25
    }

    @Test
    fun `ongoing recording is surfaced with isOngoingRecording flag`() = runTest {
        val repo = createRepo()
        writeReportDir("recording_1_aaaa", BugReport.Type.RECORDING, ongoing = true)
        // "Ongoing" requires the live recorder to own this id, not just the sentinel's presence.
        recorderState.value = BugReportRecorder.State(isRecording = true, recordingId = "recording_1_aaaa")

        val info = repo.reports.first().single { it.id == "recording_1_aaaa" }
        info.isOngoingRecording shouldBe true
        info.report.type shouldBe BugReport.Type.RECORDING
        info.report.errorClass shouldBe null
    }

    @Test
    fun `finished recording without sentinel is a normal report`() = runTest {
        val repo = createRepo()
        writeReportDir("recording_2_bbbb", BugReport.Type.RECORDING, ongoing = false)

        val info = repo.reports.first().single { it.id == "recording_2_bbbb" }
        info.isOngoingRecording shouldBe false
        info.report.type shouldBe BugReport.Type.RECORDING
    }

    @Test
    fun `recording with a stale sentinel but no active recorder is a normal report`() = runTest {
        val repo = createRepo()
        // Sentinel present (e.g. interrupted by process death) but the recorder is not recording it:
        // it must surface as a normal, complete report rather than being hidden as "ongoing".
        writeReportDir("recording_4_dddd", BugReport.Type.RECORDING, ongoing = true)

        val info = repo.reports.first().single { it.id == "recording_4_dddd" }
        info.isOngoingRecording shouldBe false
        info.report.type shouldBe BugReport.Type.RECORDING
    }

    @Test
    fun `delete refuses the active recording`() = runTest {
        val repo = createRepo()
        writeReportDir("recording_3_cccc", BugReport.Type.RECORDING, ongoing = true)
        recorderState.value = BugReportRecorder.State(isRecording = true, recordingId = "recording_3_cccc")

        shouldThrow<IllegalArgumentException> { repo.delete("recording_3_cccc") }
    }
}

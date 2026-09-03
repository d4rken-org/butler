package eu.darken.butler.common.debug.bugreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.R
import eu.darken.butler.common.debug.logging.RingLogBuffer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
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
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugReportRepoTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val storageLayout by lazy { BugReportStorageLayout(context) }

    /** Where new reports go (external app-specific storage), and the legacy root older ones live in. */
    private val reportsDir get() = storageLayout.writeRoot
    private val privateReportsDir get() = File(context.filesDir, "bugreports")
    private val json = Json { ignoreUnknownKeys = true }
    private val recorderState = MutableStateFlow(BugReportRecorder.State())

    /** Ids the recorder double reports as active-or-starting on top of the published recordingId. */
    private val startingRecordingIds = mutableSetOf<String>()

    private fun createRepo(): BugReportRepo {
        val buffer = RingLogBuffer().apply {
            log(eu.darken.butler.common.debug.logging.Logging.Priority.INFO, "Test", "log line", null)
        }
        val recorder = mockk<BugReportRecorder>(relaxed = true) {
            every { state } returns recorderState
            coEvery { isActiveOrStarting(any()) } answers {
                val id = firstArg<String>()
                id == recorderState.value.recordingId || id in startingRecordingIds
            }
        }
        return BugReportRepo(
            context = context,
            appScope = CoroutineScope(Dispatchers.Unconfined),
            dispatcherProvider = TestDispatcherProvider(),
            ringLogBuffer = buffer,
            bugReportRecorder = recorder,
            butlerId = ButlerId(context),
            json = json,
            storageLayout = storageLayout,
        )
    }

    private fun writeReportDir(
        id: String,
        type: BugReport.Type = BugReport.Type.REPORTED,
        ongoing: Boolean = false,
        root: File = reportsDir,
        logText: String = "rec log",
        createdAt: Instant = kotlin.time.Clock.System.now(),
        errorClass: String? = null,
        errorMessage: String? = null,
        label: String? = null,
    ): BugReport {
        val dir = File(root, id).apply { mkdirs() }
        val report = BugReport(
            id = id,
            createdAt = createdAt,
            type = type,
            errorClass = errorClass,
            errorMessage = errorMessage,
            appVersion = "1.0",
            deviceFingerprint = "fp",
            apiLevel = "29",
            flavor = "FOSS",
            buildType = "DEBUG",
            installId = "iid",
            locale = "en",
            label = label,
        )
        File(dir, "meta.json").writeText(json.encodeToString(BugReport.serializer(), report))
        File(dir, "report.log").writeText(logText)
        if (ongoing) File(dir, ".recording").createNewFile()
        return report
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
    fun `retention never prunes a sentinel-bearing recording even when the recorder looks idle`() = runTest {
        val repo = createRepo()
        val base = kotlin.time.Clock.System.now()
        // Oldest report of all, sentinel on disk, recorder state empty — the :isolated process view
        // of a recording the main process owns. Without the sentinel guard it is first to be pruned.
        writeReportDir(
            "recording_8_hhhh",
            BugReport.Type.RECORDING,
            ongoing = true,
            createdAt = base - 100.seconds,
        )
        repeat(30) { writeReportDir("filler_$it", createdAt = base - (30 - it).seconds) }

        repo.captureReport(IllegalStateException("boom"))

        repo.reports.first() shouldHaveSize 26
        File(reportsDir, "recording_8_hhhh").exists() shouldBe true
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

    @Test
    fun `delete refuses a recording whose start has not published its id yet`() = runTest {
        val repo = createRepo()
        // Mid-start: the directory exists while the recorder has not published the id yet, so the
        // published id alone would let this delete through.
        writeReportDir("recording_6_ffff", BugReport.Type.RECORDING, ongoing = true)
        recorderState.value = BugReportRecorder.State()
        startingRecordingIds += "recording_6_ffff"

        shouldThrow<IllegalArgumentException> { repo.delete("recording_6_ffff") }

        File(reportsDir, "recording_6_ffff").exists() shouldBe true
    }

    @Test
    fun `delete removes a report with a stale recording sentinel`() = runTest {
        val repo = createRepo()
        // Sentinel left behind by a stop that could not remove it (or by process death after the
        // startup sweep): the recorder is idle, so the report is finished and must be deletable.
        writeReportDir("recording_7_gggg", BugReport.Type.RECORDING, ongoing = true)

        repo.delete("recording_7_gggg")

        File(reportsDir, "recording_7_gggg").exists() shouldBe false
        repo.reports.first() shouldHaveSize 0
    }

    @Test
    fun `buildShareZip refuses a recording the recorder is about to claim`() = runTest {
        val repo = createRepo()
        // The startup-recovery window: the report is listed as complete while the recorder has not
        // published the id yet. The share guard goes through the recorder mutex, so it must reject.
        writeReportDir("recording_9_iiii", BugReport.Type.RECORDING, ongoing = true)
        startingRecordingIds += "recording_9_iiii"

        shouldThrow<IllegalArgumentException> { repo.buildShareZip("recording_9_iiii") }
    }

    @Test
    fun `logSizeBytes reflects the on-disk log size`() = runTest {
        val repo = createRepo()
        repo.captureCrashBlocking(IllegalStateException("boom"), Thread.currentThread())

        val info = repo.reports.first().single()
        (info.logSizeBytes > 0L) shouldBe true
    }

    @Test
    fun `readLogTail returns all lines for a short log`() = runTest {
        val repo = createRepo()
        writeReportDir("tail_short", logText = "a\nb\nc")

        val tail = repo.readLogTail("tail_short", maxLines = 10)
        tail.totalLines shouldBe 3
        tail.lines shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `readLogTail returns only the tail for a long log`() = runTest {
        val repo = createRepo()
        writeReportDir("tail_long", logText = (1..100).joinToString("\n") { "line$it" })

        val tail = repo.readLogTail("tail_long", maxLines = 10)
        tail.totalLines shouldBe 100
        tail.lines shouldHaveSize 10
        tail.lines.first() shouldBe "line91"
        tail.lines.last() shouldBe "line100"
    }

    @Test
    fun `readLogTail on a missing log is empty`() = runTest {
        val repo = createRepo()

        val tail = repo.readLogTail("does_not_exist", maxLines = 10)
        tail.totalLines shouldBe 0
        tail.lines shouldHaveSize 0
    }

    @Test
    fun `reports from both roots are listed`() = runTest {
        val repo = createRepo()
        writeReportDir("external_1")
        writeReportDir("legacy_1", root = privateReportsDir)

        repo.reports.first().map { it.id } shouldContainExactlyInAnyOrder listOf("external_1", "legacy_1")
    }

    @Test
    fun `an id present in both roots is listed once, from the external root`() = runTest {
        val repo = createRepo()
        writeReportDir("dupe_1", logText = "external log")
        writeReportDir("dupe_1", root = privateReportsDir, logText = "legacy log")

        repo.reports.first() shouldHaveSize 1
        repo.readLog("dupe_1") shouldBe "external log"
    }

    @Test
    fun `delete removes every physical copy of an id`() = runTest {
        val repo = createRepo()
        writeReportDir("dupe_2")
        writeReportDir("dupe_2", root = privateReportsDir)

        repo.delete("dupe_2")

        File(reportsDir, "dupe_2").exists() shouldBe false
        File(privateReportsDir, "dupe_2").exists() shouldBe false
        repo.reports.first() shouldHaveSize 0
    }

    @Test
    fun `a corrupt external copy does not shadow the readable legacy one`() = runTest {
        val repo = createRepo()
        writeReportDir("dupe_3", logText = "external log")
        File(File(reportsDir, "dupe_3"), "meta.json").writeText("{ not valid json")
        writeReportDir("dupe_3", root = privateReportsDir, logText = "legacy log")

        repo.reports.first().single().id shouldBe "dupe_3"
        repo.readLog("dupe_3") shouldBe "legacy log"
        // Sharing must follow the same copy the list shows, not the corrupt one it skipped.
        ZipFile(repo.buildShareZip("dupe_3")).use { zip ->
            zip.getInputStream(zip.getEntry("report.log")).readBytes().decodeToString()
        } shouldBe "legacy log"
    }

    @Test
    fun `buildShareZip packages the report payload and nothing else`() = runTest {
        val repo = createRepo()
        writeReportDir("share_1", logText = "report log")
        val dir = File(reportsDir, "share_1")
        File(dir, "root.log").writeText("root log")
        File(dir, "adb.log").writeText("adb log")
        File(dir, ".seen").createNewFile()
        File(dir, ".recording").createNewFile()
        File(dir, "injected.txt").writeText("not mine")

        val zip = repo.buildShareZip("share_1")

        ZipFile(zip).use { it.entries().toList().map { entry -> entry.name } } shouldContainExactly
            listOf("meta.json", "report.log", "root.log", "adb.log")
    }

    @Test
    fun `a failed buildShareZip leaves no zip behind`() = runTest {
        val repo = createRepo()
        writeReportDir("share_2")
        val shareDir = File(context.cacheDir, "bugreports_share")
        // The zip is built under this name before being renamed into place; an (empty) directory
        // sitting there makes the compression fail on its very first write.
        File(shareDir, "share_2.zip.tmp").mkdirs()

        shouldThrow<Exception> { repo.buildShareZip("share_2") }

        File(shareDir, "share_2.zip").exists() shouldBe false
        File(shareDir, "share_2.zip.tmp").exists() shouldBe false
    }

    @Test
    fun `markSeen, readLog and readLogTail work on a report in the legacy root`() = runTest {
        val repo = createRepo()
        writeReportDir("legacy_2", root = privateReportsDir, logText = "l1\nl2")

        repo.markSeen("legacy_2")

        repo.reports.first().single { it.id == "legacy_2" }.isSeen shouldBe true
        repo.readLog("legacy_2") shouldBe "l1\nl2"
        repo.readLogTail("legacy_2", maxLines = 10).lines shouldBe listOf("l1", "l2")
    }

    @Test
    fun `retention also prunes reports in the legacy root`() = runTest {
        val repo = createRepo()
        val base = kotlin.time.Clock.System.now()
        repeat(30) { index ->
            writeReportDir(
                id = "legacy_prune_$index",
                root = privateReportsDir,
                createdAt = base - (30 - index).seconds,
            )
        }

        // captureReport writes a fresh report and prunes afterwards.
        repo.captureReport(IllegalStateException("boom"))

        repo.reports.first() shouldHaveSize 25
        File(privateReportsDir, "legacy_prune_0").exists() shouldBe false
        File(privateReportsDir, "legacy_prune_29").exists() shouldBe true
    }

    @Test
    fun `stale temp dirs are reaped in the legacy root`() = runTest {
        val stale = File(privateReportsDir, ".tmp-legacy").apply { mkdirs() }
        stale.setLastModified(System.currentTimeMillis() - 5 * 60_000L)

        // The init cleanup runs the reaper.
        createRepo()

        stale.exists() shouldBe false
    }

    private fun storedReport(id: String, root: File = reportsDir): BugReport = json.decodeFromString(
        BugReport.serializer(),
        File(File(root, id), "meta.json").readText(),
    )

    @Test
    fun `setLabel round-trips through meta json`() = runTest {
        val repo = createRepo()
        writeReportDir("label_1")

        repo.setLabel("label_1", "Copy stalls on SD card")

        storedReport("label_1").label shouldBe "Copy stalls on SD card"
        repo.reports.first().single { it.id == "label_1" }.report.label shouldBe "Copy stalls on SD card"
    }

    @Test
    fun `setLabel clears the name again`() = runTest {
        val repo = createRepo()
        writeReportDir("label_2", label = "Named")

        repo.setLabel("label_2", "   ")

        storedReport("label_2").label shouldBe null
    }

    @Test
    fun `a label is normalized before it is stored`() = runTest {
        val repo = createRepo()
        writeReportDir("label_3")

        repo.setLabel("label_3", "  Copy\u00A0\n stalls\u0000 ")

        storedReport("label_3").label shouldBe "Copy stalls"
    }

    @Test
    fun `normalization collapses whitespace runs and drops control characters`() {
        BugReport.normalizeLabel("a\u00A0\u00A0b") shouldBe "a b"
        BugReport.normalizeLabel("a\u2028b") shouldBe "a b"
        BugReport.normalizeLabel("a\tb\nc") shouldBe "a b c"
        BugReport.normalizeLabel("a\u0007b") shouldBe "ab"
        BugReport.normalizeLabel("  spaced  out  ") shouldBe "spaced out"
    }

    @Test
    fun `normalization treats a blank label as a clear`() {
        BugReport.normalizeLabel(null) shouldBe null
        BugReport.normalizeLabel("") shouldBe null
        BugReport.normalizeLabel("   ") shouldBe null
        BugReport.normalizeLabel("\u0000\u0001") shouldBe null
    }

    @Test
    fun `an over-length label is capped without a trailing space`() {
        val label = BugReport.normalizeLabel("a".repeat(BugReport.MAX_LABEL_LENGTH - 1) + " b")

        label shouldBe "a".repeat(BugReport.MAX_LABEL_LENGTH - 1)
    }

    // Cutting at char 128 would land inside the pair and leave a lone surrogate behind.
    @Test
    fun `an emoji straddling the cap is dropped whole`() {
        val label = BugReport.normalizeLabel("a".repeat(BugReport.MAX_LABEL_LENGTH - 1) + "\uD83D\uDC1B" + "b")!!

        label shouldBe "a".repeat(BugReport.MAX_LABEL_LENGTH - 1) + "\uD83D\uDC1B"
        label.count { it.isSurrogate() } shouldBe 2
    }

    @Test
    fun `a meta json without a label key still parses`() = runTest {
        val repo = createRepo()
        val dir = File(reportsDir, "label_4").apply { mkdirs() }
        File(dir, "meta.json").writeText(
            """{"id":"label_4","createdAt":"2026-06-15T10:00:00Z","type":"REPORTED","appVersion":"1.0",""" +
                """"deviceFingerprint":"fp","apiLevel":"29","flavor":"FOSS","buildType":"DEBUG",""" +
                """"installId":"iid","locale":"en"}""",
        )
        File(dir, "report.log").writeText("log")

        repo.reports.first().single { it.id == "label_4" }.report.label shouldBe null
    }

    @Test
    fun `setLabel on a report that is gone does nothing`() = runTest {
        val repo = createRepo()

        repo.setLabel("label_gone", "Name")

        File(reportsDir, "label_gone").exists() shouldBe false
    }

    @Test
    fun `setLabel updates every physical copy of an id`() = runTest {
        val repo = createRepo()
        writeReportDir("label_5")
        writeReportDir("label_5", root = privateReportsDir)

        repo.setLabel("label_5", "Named")

        storedReport("label_5").label shouldBe "Named"
        storedReport("label_5", root = privateReportsDir).label shouldBe "Named"
    }

    @Test
    fun `a failed metadata write leaves the report readable under its old name`() = runTest {
        val repo = createRepo()
        writeReportDir("label_6", label = "Before")
        // The new metadata is staged under this name before replacing meta.json; an (empty) directory
        // sitting there makes the staging write fail.
        File(File(reportsDir, "label_6"), "meta.json.tmp").mkdirs()

        shouldThrow<Exception> { repo.setLabel("label_6", "After") }

        storedReport("label_6").label shouldBe "Before"
        repo.reports.first().single { it.id == "label_6" }.report.label shouldBe "Before"
    }

    // The recorder writes meta.json only when a recording starts, so a rename mid-recording sticks.
    @Test
    fun `a recording can be renamed while it is running`() = runTest {
        val repo = createRepo()
        writeReportDir("recording_10_jjjj", BugReport.Type.RECORDING, ongoing = true)
        recorderState.value = BugReportRecorder.State(isRecording = true, recordingId = "recording_10_jjjj")
        startingRecordingIds += "recording_10_jjjj"

        repo.setLabel("recording_10_jjjj", "Stalling copy")

        storedReport("recording_10_jjjj").label shouldBe "Stalling copy"
    }

    private val whatPrompt: String
        get() = context.getString(R.string.general_bug_report_body_what_happened_prompt)

    private val expectedPrompt: String
        get() = context.getString(R.string.general_bug_report_body_expected_prompt)

    @Test
    fun `the share body carries both prompts and the report metadata`() {
        val repo = createRepo()
        val report = writeReportDir("body_1")

        repo.buildShareBody("body_1", report) shouldBe listOf(
            "--- What happened? ---",
            whatPrompt,
            "",
            "--- Expected behavior ---",
            expectedPrompt,
            "",
            "--- Report info ---",
            "App: 1.0",
            "Device: fp",
            "Android: API 29",
            "Type: REPORTED",
            "Report: body_1",
            "",
            "Details are in the attached zip.",
        ).joinToString("\n")
    }

    @Test
    fun `a crash body names the error and its first message line`() {
        val repo = createRepo()
        val report = writeReportDir(
            id = "body_2",
            type = BugReport.Type.CRASH,
            errorClass = "java.lang.IllegalStateException",
            errorMessage = "boom\nsecond line",
        )

        val body = repo.buildShareBody("body_2", report)

        body shouldContain "Error: java.lang.IllegalStateException: boom"
        body shouldNotContain "second line"
    }

    // The fixture carries error fields a real recording never has, so the assertion pins the type
    // gate rather than the metadata just happening to be null.
    @Test
    fun `a recording body has no error line`() {
        val repo = createRepo()
        val report = writeReportDir(
            id = "body_3",
            type = BugReport.Type.RECORDING,
            errorClass = "java.lang.IllegalStateException",
            errorMessage = "boom",
        )

        repo.buildShareBody("body_3", report) shouldNotContain "Error:"
    }

    @Test
    fun `a body without metadata still identifies the report`() {
        val repo = createRepo()

        repo.buildShareBody("body_4", null) shouldBe listOf(
            "--- What happened? ---",
            whatPrompt,
            "",
            "--- Expected behavior ---",
            expectedPrompt,
            "",
            "--- Report info ---",
            "Report: body_4",
            "",
            "Details are in the attached zip.",
        ).joinToString("\n")
    }
}

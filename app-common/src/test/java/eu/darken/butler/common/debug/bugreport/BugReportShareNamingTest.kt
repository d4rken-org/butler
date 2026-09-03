package eu.darken.butler.common.debug.bugreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.debug.logging.RingLogBuffer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

/** How a report's name reaches its share zip, and what happens to zips named after an older one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugReportShareNamingTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val storageLayout by lazy { BugReportStorageLayout(context) }
    private val reportsDir get() = storageLayout.writeRoot
    private val shareDir get() = File(context.cacheDir, BugReportStorage.SHARE_DIRNAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val recorderState = MutableStateFlow(BugReportRecorder.State())

    private fun createRepo(): BugReportRepo {
        val recorder = mockk<BugReportRecorder>(relaxed = true) {
            every { state } returns recorderState
            coEvery { isActiveOrStarting(any()) } answers {
                firstArg<String>() == recorderState.value.recordingId
            }
        }
        return BugReportRepo(
            context = context,
            appScope = CoroutineScope(Dispatchers.Unconfined),
            dispatcherProvider = TestDispatcherProvider(),
            ringLogBuffer = RingLogBuffer(),
            bugReportRecorder = recorder,
            butlerId = ButlerId(context),
            json = json,
            storageLayout = storageLayout,
        )
    }

    private fun writeReportDir(
        id: String,
        label: String? = null,
        ongoing: Boolean = false,
        logText: String = "log line",
        createdAt: Instant = kotlin.time.Clock.System.now(),
    ): BugReport {
        val dir = File(reportsDir, id).apply { mkdirs() }
        val report = BugReport(
            id = id,
            createdAt = createdAt,
            type = BugReport.Type.REPORTED,
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

    private fun writeShareZip(name: String) {
        shareDir.mkdirs()
        File(shareDir, name).writeText("zip")
    }

    private fun shareZipNames(): List<String> = (shareDir.listFiles() ?: emptyArray())
        .map { it.name }
        .sorted()

    @Test
    fun `an unnamed report keeps the bare id as its zip name`() = runTest {
        val repo = createRepo()
        writeReportDir("share_plain")

        repo.buildShareZip("share_plain").name shouldBe "share_plain.zip"
    }

    @Test
    fun `a named report carries its name in the zip name`() = runTest {
        val repo = createRepo()
        writeReportDir("share_named", label = "Copy stalls on SD card")

        repo.buildShareZip("share_named").name shouldBe "Copy stalls on SD card_share_named.zip"
    }

    @Test
    fun `a non-ASCII name survives into the zip name`() = runTest {
        val repo = createRepo()
        writeReportDir("share_de", label = "Küche")
        writeReportDir("share_zh", label = "厨房")

        repo.buildShareZip("share_de").name shouldBe "Küche_share_de.zip"
        repo.buildShareZip("share_zh").name shouldBe "厨房_share_zh.zip"
    }

    @Test
    fun `a name made of separators falls back to the bare id`() = runTest {
        val repo = createRepo()
        writeReportDir("share_seps", label = "/\\:*")

        repo.buildShareZip("share_seps").name shouldBe "share_seps.zip"
    }

    @Test
    fun `a name starting with a dot does not produce a hidden file`() = runTest {
        val repo = createRepo()
        writeReportDir("share_dot", label = ".hidden")

        repo.buildShareZip("share_dot").name shouldBe "hidden_share_dot.zip"
    }

    @Test
    fun `rebuilding after a rename leaves only the zip under the current name`() = runTest {
        val repo = createRepo()
        writeReportDir("share_rebuilt", label = "Before")
        repo.buildShareZip("share_rebuilt")

        repo.setLabel("share_rebuilt", "After")
        repo.buildShareZip("share_rebuilt")

        shareZipNames() shouldContainExactly listOf("After_share_rebuilt.zip")
    }

    @Test
    fun `delete removes a named zip and the leftovers of a crashed build`() = runTest {
        val repo = createRepo()
        writeReportDir("share_del", label = "Named")
        writeShareZip("Named_share_del.zip")
        writeShareZip("Older_share_del.zip.tmp")
        writeShareZip("keep_me_share_other.zip")

        repo.delete("share_del")

        shareZipNames() shouldContainExactly listOf("keep_me_share_other.zip")
    }

    @Test
    fun `deleteAll removes named zips but keeps the active recording's`() = runTest {
        val repo = createRepo()
        writeReportDir("recording_live", label = "Live", ongoing = true)
        recorderState.value = BugReportRecorder.State(isRecording = true, recordingId = "recording_live")
        writeReportDir("share_old", label = "Old")
        writeShareZip("Live_recording_live.zip")
        writeShareZip("Old_share_old.zip")
        writeShareZip("Old_share_old.zip.tmp")

        repo.deleteAll()

        shareZipNames() shouldContainExactly listOf("Live_recording_live.zip")
    }

    @Test
    fun `retention removes the named zip of a pruned report`() = runTest {
        val repo = createRepo()
        val base = kotlin.time.Clock.System.now()
        repeat(30) { index ->
            writeReportDir("prune_$index", label = "Name$index", createdAt = base - (30 - index).seconds)
        }
        writeShareZip("Name0_prune_0.zip")
        writeShareZip("Name29_prune_29.zip")

        // captureReport writes a fresh report and prunes afterwards.
        repo.captureReport(IllegalStateException("boom"))

        shareZipNames() shouldContainExactly listOf("Name29_prune_29.zip")
    }

    @Test
    fun `the share body names the report only when it has a name`() {
        val repo = createRepo()
        val named = writeReportDir("body_named", label = "Copy stalls")
        val plain = writeReportDir("body_plain")

        repo.buildShareBody("body_named", named) shouldContain "Name: Copy stalls"
        repo.buildShareBody("body_plain", plain) shouldNotContain "Name:"
    }

    /**
     * A rename that lands while the zip is being compressed must not produce a zip named after the
     * old name whose sealed `meta.json` carries the new one. The build runs on a real thread and the
     * rename is fired once the compression has started, so the two genuinely overlap.
     */
    @Test
    fun `a rename cannot interleave with a zip build`() = runBlocking<Unit> {
        val repo = createRepo()
        val bulkyLog = (1..200_000).joinToString("\n") { "line $it ${it * 7919}" }
        writeReportDir("race_1", label = "Before", logText = bulkyLog)

        val zipJob = launch(Dispatchers.IO) { repo.buildShareZip("race_1") }
        val deadline = System.currentTimeMillis() + 10_000
        while (
            shareDir.listFiles()?.none { it.name.endsWith(BugReportStorage.TMP_SUFFIX) } != false &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(1)
        }

        repo.setLabel("race_1", "After")
        zipJob.join()

        val zip = shareDir.listFiles()!!.single()
        val sealed = ZipFile(zip).use { file ->
            json.decodeFromString(
                BugReport.serializer(),
                file.getInputStream(file.getEntry("meta.json")).readBytes().decodeToString(),
            )
        }
        zip.name shouldBe BugReportStorage.shareZipName("race_1", sealed.label)
    }
}

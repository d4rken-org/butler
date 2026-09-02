package eu.darken.butler.common.debug.bugreport

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.R
import eu.darken.butler.common.debug.logging.RingLogBuffer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import kotlin.time.Instant

/**
 * Lives in :app because [androidx.core.content.FileProvider] resolves against the application's
 * provider authority and its path XML, which only the app manifest declares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BugReportShareIntentTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val storageLayout by lazy { BugReportStorageLayout(context) }
    private val json = Json { ignoreUnknownKeys = true }

    private fun createRepo(): BugReportRepo {
        val recorder = mockk<BugReportRecorder>(relaxed = true) {
            every { state } returns MutableStateFlow(BugReportRecorder.State())
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

    private fun writeReportDir(id: String): BugReport {
        val dir = File(storageLayout.writeRoot, id).apply { mkdirs() }
        val report = BugReport(
            id = id,
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            type = BugReport.Type.CRASH,
            errorClass = "java.lang.IllegalStateException",
            errorMessage = "boom",
            appVersion = "1.0",
            deviceFingerprint = "fp",
            apiLevel = "29",
            flavor = "FOSS",
            buildType = "DEBUG",
            installId = "iid",
            locale = "en",
        )
        File(dir, "meta.json").writeText(json.encodeToString(BugReport.serializer(), report))
        File(dir, "report.log").writeText("log line")
        return report
    }

    @Test
    fun `the share intent carries the subject, the body and a readable zip`() = runTest {
        val repo = createRepo()
        writeReportDir("crash_1")

        val intent = repo.buildShareIntent("crash_1")

        intent.action shouldBe Intent.ACTION_SEND
        intent.type shouldBe "application/zip"

        intent.getStringExtra(Intent.EXTRA_SUBJECT) shouldBe context.getString(
            R.string.general_bug_report_subject,
            context.getString(R.string.app_name),
            "crash_1",
        )

        val body = intent.getStringExtra(Intent.EXTRA_TEXT)!!
        body shouldContain context.getString(R.string.general_bug_report_body_what_happened_prompt)
        body shouldContain context.getString(R.string.general_bug_report_body_expected_prompt)
        body shouldContain "Error: java.lang.IllegalStateException: boom"
        body shouldContain "Report: crash_1"

        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        uri.toString() shouldContain "crash_1.zip"
        intent.clipData!!.getItemAt(0).uri shouldBe uri
        (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) shouldBe Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}

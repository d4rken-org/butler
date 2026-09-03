package eu.darken.butler.main.ui.settings.support.contactform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.EmailTool
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.debug.bugreport.BugReportRecorder
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant

/** The support mail names the report the user attached, so a mail and a zip can be matched up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportContactFormBodyTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val recorderState = MutableStateFlow(BugReportRecorder.State())
    private val reportsFlow = MutableStateFlow<List<BugReportInfo>>(emptyList())
    private val emailTool = mockk<EmailTool>()
    private val emailSlot = slot<EmailTool.Email>()

    private fun info(id: String, label: String?) = BugReportInfo(
        report = BugReport(
            id = id,
            createdAt = Instant.parse("2026-06-15T10:00:00Z"),
            type = BugReport.Type.CRASH,
            errorClass = "java.lang.IllegalStateException",
            appVersion = "1.0",
            deviceFingerprint = "fp",
            apiLevel = "34",
            flavor = "FOSS",
            buildType = "DEBUG",
            installId = "iid",
            locale = "en",
            label = label,
        ),
        isSeen = true,
    )

    private fun createVM(): SupportContactFormViewModel {
        every { emailTool.build(capture(emailSlot), any()) } returns Intent()
        return SupportContactFormViewModel(
            dispatcherProvider = TestDispatcherProvider(),
            context = context,
            bugReportRecorder = mockk(relaxed = true) {
                every { state } returns recorderState
            },
            bugReportRepo = mockk(relaxed = true) {
                every { reports } returns reportsFlow
                coEvery { buildShareUri(any()) } returns Uri.parse("content://test/zip")
            },
            emailTool = emailTool,
        )
    }

    private fun SupportContactFormViewModel.fillAndSend(selectedId: String) {
        updateCategory(SupportContactFormViewModel.Category.BUG)
        updateDescription(List(25) { "word$it" }.joinToString(" "))
        updateExpectedBehavior(List(15) { "word$it" }.joinToString(" "))
        selectReport(selectedId)
        send()
    }

    @Test
    fun `the body names the selected report`() = runTest {
        reportsFlow.value = listOf(info("crash_1", label = "Copy stalls on SD card"))
        val vm = createVM()

        vm.fillAndSend("crash_1")

        emailSlot.captured.body shouldContain "Report: Copy stalls on SD card"
    }

    @Test
    fun `an unnamed report adds no report line`() = runTest {
        reportsFlow.value = listOf(info("crash_2", label = null))
        val vm = createVM()

        vm.fillAndSend("crash_2")

        emailSlot.captured.body shouldNotContain "Report:"
    }
}

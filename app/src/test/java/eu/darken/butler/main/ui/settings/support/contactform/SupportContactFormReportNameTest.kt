package eu.darken.butler.main.ui.settings.support.contactform

import android.content.Context
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import kotlin.time.Instant

/** A user-set report name replaces the automatic "Crash report · IllegalStateException" identification. */
@Config(qualifiers = "w400dp-h800dp")
class SupportContactFormReportNameTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

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

    private fun setContent(vararg reports: BugReportInfo) {
        composeTestRule.setContent {
            PreviewWrapper {
                SupportContactFormScreen(
                    state = SupportContactFormViewModel.State(
                        category = SupportContactFormViewModel.Category.BUG,
                        reports = reports.toList(),
                    ),
                )
            }
        }
    }

    @Test
    fun `a named report is listed under its name`() {
        setContent(info("crash_1", label = "Copy stalls on SD card"))

        composeTestRule.onNodeWithText("Copy stalls on SD card").assertExists()
    }

    @Test
    fun `an unnamed report keeps its automatic identification`() {
        setContent(info("crash_2", label = null))

        val expected = context.getString(R.string.support_contact_report_type_crash) + " · IllegalStateException"
        composeTestRule.onNodeWithText(expected).assertExists()
    }
}

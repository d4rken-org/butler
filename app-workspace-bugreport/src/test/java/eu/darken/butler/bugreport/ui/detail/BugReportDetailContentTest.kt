package eu.darken.butler.bugreport.ui.detail

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.bugreport.R
import eu.darken.butler.bugreport.ui.BugReportWorkspaceViewModel
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import kotlin.time.Instant

/** The log tail is only rendered while the section is expanded; the header is the toggle. */
@Config(qualifiers = "w400dp-h800dp")
class BugReportDetailContentTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logSectionTitle = context.getString(R.string.bugreport_detail_section_log)
    private val expandLabel = context.getString(eu.darken.butler.common.R.string.general_expand_action)
    private val collapseLabel = context.getString(eu.darken.butler.common.R.string.general_collapse_action)
    private val logSizeBytes = 24_000L

    // No error fields: the detail then renders without the error card, which keeps the log section
    // on screen in Robolectric's fixed-size wrapper.
    private val info = BugReportInfo(
        report = BugReport(
            id = "report-1",
            createdAt = Instant.parse("2026-06-15T10:00:00Z"),
            type = BugReport.Type.RECORDING,
            errorClass = null,
            errorMessage = null,
            stackTrace = null,
            threadName = null,
            appVersion = "v0.0.0-beta1",
            deviceFingerprint = "Pixel/foo",
            apiLevel = "36",
            flavor = "FOSS",
            buildType = "RELEASE",
            installId = "abc",
            locale = "en-US",
        ),
        isSeen = true,
        logSizeBytes = logSizeBytes,
    )

    private val loaded = BugReportWorkspaceViewModel.LogState.Loaded(
        lines = listOf(MARKER_LINE),
        totalLines = 1,
        shownLines = 1,
        isTruncated = false,
    )

    private fun setContent(
        logState: BugReportWorkspaceViewModel.LogState,
        isLogExpanded: Boolean,
        onToggleLog: (Boolean) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                BugReportDetailContent(
                    design = WorkspaceDesign(),
                    detail = BugReportWorkspaceViewModel.Detail(
                        info = info,
                        logState = logState,
                        isLogExpanded = isLogExpanded,
                    ),
                    onBack = {},
                    onRename = {},
                    onShare = {},
                    onDelete = {},
                    onToggleLog = onToggleLog,
                )
            }
        }
    }

    @Test
    fun `a collapsed section renders no log body but keeps the header summary`() {
        setContent(logState = loaded, isLogExpanded = false)

        composeTestRule.onNodeWithText(MARKER_LINE).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(expandLabel).assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.bugreport_detail_log_count, 1), substring = true)
            .assertExists()
    }

    @Test
    fun `the default state shows the log size without having read the log`() {
        setContent(logState = BugReportWorkspaceViewModel.LogState.Idle, isLogExpanded = false)

        composeTestRule.onNodeWithText(formatFileSize(context, logSizeBytes)).assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.bugreport_detail_log_count, 1), substring = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(expandLabel).assertExists()
    }

    @Test
    fun `an expanded section renders the log body`() {
        setContent(logState = loaded, isLogExpanded = true)

        composeTestRule.onNodeWithText(MARKER_LINE).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(collapseLabel).assertExists()
    }

    @Test
    fun `tapping the header requests the expansion`() {
        var toggledTo: Boolean? = null
        setContent(
            logState = BugReportWorkspaceViewModel.LogState.Idle,
            isLogExpanded = false,
            onToggleLog = { toggledTo = it },
        )

        // Via the semantics action, not performClick: in this fixed-size wrapper a laid-out but
        // off-screen node swallows a click without reporting a failure.
        composeTestRule.onNodeWithText(logSectionTitle).performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle { toggledTo shouldBe true }
    }

    companion object {
        private const val MARKER_LINE = "MARKER-LINE"
    }
}

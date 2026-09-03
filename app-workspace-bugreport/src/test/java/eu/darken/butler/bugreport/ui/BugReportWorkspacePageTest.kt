package eu.darken.butler.bugreport.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.bugreport.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import kotlin.time.Instant

/** Recording is started and stopped from the bottom bar, not from the toolbar card. */
@Config(qualifiers = "w400dp-h800dp")
class BugReportWorkspacePageTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val recordLabel = context.getString(R.string.bugreport_record_action)
    private val stopLabel = context.getString(R.string.bugreport_stop_action)
    private val deleteAllLabel = context.getString(R.string.bugreport_delete_all_action)

    private fun report(index: Int, label: String? = null) = BugReportInfo(
        report = BugReport(
            id = "report-$index",
            createdAt = Instant.parse("2026-06-15T10:00:00Z"),
            type = BugReport.Type.CRASH,
            errorClass = "java.lang.IllegalStateException",
            errorMessage = "failure number $index",
            stackTrace = "",
            threadName = "main",
            appVersion = "v0.0.0-beta1",
            deviceFingerprint = "Pixel/foo",
            apiLevel = "36",
            flavor = "FOSS",
            buildType = "RELEASE",
            installId = "abc",
            locale = "en-US",
            label = label,
        ),
        isSeen = true,
    )

    private fun setPage(
        reports: List<BugReportInfo> = emptyList(),
        isRecording: Boolean = false,
        onStartRecording: () -> Unit = {},
        onStopRecording: () -> Unit = {},
        onDeleteAll: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                BugReportWorkspacePage(
                    state = BugReportWorkspaceViewModel.State(
                        id = Workspace.Id(),
                        reports = reports,
                        isRecording = isRecording,
                    ),
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onDeleteAll = onDeleteAll,
                )
            }
        }
    }

    @Test
    fun `the record button is reachable without any reports`() {
        var started = 0
        setPage(onStartRecording = { started++ })

        composeTestRule.onNodeWithContentDescription(recordLabel).assertIsDisplayed().performClick()

        composeTestRule.runOnIdle { started shouldBe 1 }
    }

    @Test
    fun `the record button stops an ongoing recording`() {
        var stopped = 0
        setPage(isRecording = true, onStopRecording = { stopped++ })

        // By content description, not by the visible "Stop": the toolbar's recording row renders a
        // Stop button of its own.
        composeTestRule.onNodeWithContentDescription(stopLabel).assertIsDisplayed().performClick()

        composeTestRule.runOnIdle { stopped shouldBe 1 }
    }

    @Test
    fun `only the record button offers to start a recording`() {
        setPage(reports = listOf(report(1)))

        composeTestRule.onAllNodesWithContentDescription(recordLabel).assertCountEquals(1)
    }

    @Test
    fun `the record button stays reachable after scrolling the list`() {
        var started = 0
        val reports = (1..40).map { report(it) }
        setPage(reports = reports, onStartRecording = { started++ })

        val firstMessage = reports.first().report.errorMessage!!
        composeTestRule.onNodeWithText(firstMessage).assertIsDisplayed()

        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(reports.lastIndex)

        composeTestRule.onNodeWithText(firstMessage).assertIsNotDisplayed()
        composeTestRule.onNodeWithContentDescription(recordLabel).assertIsDisplayed().performClick()

        composeTestRule.runOnIdle { started shouldBe 1 }
    }

    @Test
    fun `deleting all reports is routed through the caller`() {
        var deleteAlls = 0
        setPage(reports = listOf(report(1)), onDeleteAll = { deleteAlls++ })

        // CutoutCard's Auto mode subcomposes the toolbar twice (measure + card), so the toolbar
        // controls exist twice in the semantics tree; both close over the same callback.
        composeTestRule.onAllNodesWithContentDescription(deleteAllLabel)
            .onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle { deleteAlls shouldBe 1 }
    }

    @Test
    fun `a named report shows its name above the automatic one`() {
        setPage(reports = listOf(report(1, label = "Copy stalls on SD card")))

        composeTestRule.onNodeWithText("Copy stalls on SD card").assertExists()
        // The type and error class stay visible: they are what the report is actually about.
        val autoTitle = context.getString(R.string.bugreport_type_crash) + " — IllegalStateException"
        composeTestRule.onNodeWithText(autoTitle).assertExists()
    }

    @Test
    fun `an unnamed report shows only the automatic title`() {
        setPage(reports = listOf(report(2)))

        val autoTitle = context.getString(R.string.bugreport_type_crash) + " — IllegalStateException"
        composeTestRule.onNodeWithText(autoTitle).assertExists()
    }
}

package eu.darken.butler.bugreport.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.bugreport.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class BugReportToolbarCardTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val title: String
        get() = context.getString(R.string.bugreport_workspace_title)
    private val deleteAllLabel: String
        get() = context.getString(R.string.bugreport_delete_all_action)
    private val logSizeLabel: String
        get() = Formatter.formatShortFileSize(context, LOG_SIZE)

    private var expandCalls = 0
    private var deleteAllCalls = 0

    private fun setCard(
        isCollapsed: Boolean = true,
        isRecording: Boolean = false,
        hasReports: Boolean = false,
        withExpand: Boolean = true,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                BugReportToolbarCard(
                    workspaceId = Workspace.Id(),
                    // Split-pane layout: keeps the mascot-bearing workspace button, which
                    // Robolectric cannot rasterise, out of the toolbar cutout.
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    hasReports = hasReports,
                    isRecording = isRecording,
                    recordingStartedAt = System.currentTimeMillis(),
                    recordingLogSize = LOG_SIZE,
                    isCollapsed = isCollapsed,
                    onExpand = if (withExpand) {
                        { expandCalls++ }
                    } else {
                        null
                    },
                    onStopRecording = {},
                    onDeleteAll = { deleteAllCalls++ },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun tapCentreOfTitle() {
        composeTestRule.onNodeWithText(title, useUnmergedTree = true).performTouchInput { click() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `tapping the collapsed title asks the bar to expand`() {
        setCard()

        tapCentreOfTitle()

        expandCalls shouldBe 1
    }

    @Test
    fun `tapping the collapsed recording readout asks the bar to expand`() {
        setCard(isRecording = true)

        composeTestRule.onNode(hasText(logSizeLabel, substring = true), useUnmergedTree = true)
            .performTouchInput { click() }
        composeTestRule.waitForIdle()

        expandCalls shouldBe 1
    }

    @Test
    fun `the expanded header keeps delete-all and offers no expand target`() {
        setCard(isCollapsed = false, hasReports = true)

        tapCentreOfTitle()
        expandCalls shouldBe 0

        composeTestRule.onNodeWithContentDescription(deleteAllLabel).performTouchInput { click() }
        composeTestRule.waitForIdle()

        deleteAllCalls shouldBe 1
        expandCalls shouldBe 0
    }

    @Test
    fun `the collapsed title is not tappable without an expand handler`() {
        setCard(withExpand = false)

        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction() and hasText(title)).assertCountEquals(0)
    }

    companion object {
        private const val LOG_SIZE = 2048L
    }
}

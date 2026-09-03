package eu.darken.butler.bugreport.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.bugreport.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.debug.bugreport.BugReportRecorder
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant

/** The detail toolbar's Delete asks first: the report is only removed once the dialog is confirmed. */
@Config(qualifiers = "w400dp-h800dp")
class BugReportDeleteFlowTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backLabel = context.getString(R.string.bugreport_detail_back_action)
    private val deleteLabel = context.getString(R.string.bugreport_delete_action)
    private val cancelLabel = context.getString(R.string.bugreport_cancel_action)
    private val confirmTitle = context.getString(R.string.bugreport_delete_confirm_title)

    private val report = BugReportInfo(
        report = BugReport(
            id = "report-1",
            createdAt = Instant.parse("2026-06-15T10:00:00Z"),
            type = BugReport.Type.CRASH,
            errorClass = "java.lang.IllegalStateException",
            errorMessage = "failure number 1",
            stackTrace = "",
            threadName = "main",
            appVersion = "v0.0.0-beta1",
            deviceFingerprint = "Pixel/foo",
            apiLevel = "36",
            flavor = "FOSS",
            buildType = "RELEASE",
            installId = "abc",
            locale = "en-US",
        ),
        isSeen = true,
    )

    private val bugReportRepo = mockk<BugReportRepo>(relaxed = true).apply {
        every { reports } returns flowOf(listOf(report))
    }

    private val workspaceId = Workspace.Id()

    private val vm = BugReportWorkspaceViewModel(
        id = workspaceId,
        dispatchers = TestDispatcherProvider(),
        bugReportRepo = bugReportRepo,
        bugReportRecorder = mockk<BugReportRecorder>(relaxed = true).apply {
            every { state } returns MutableStateFlow(BugReportRecorder.State())
        },
    )

    /** Page and overlays are siblings sharing one ViewModel, the same way the pane hosts them. */
    private fun setDetail() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    BugReportWorkspacePageHost(id = workspaceId, design = WorkspaceDesign(), vm = vm)
                    BugReportWorkspaceOverlaysHost(id = workspaceId, vm = vm)
                }
            }
        }
        vm.openReport(report.id)
        composeTestRule.waitForIdle()
    }

    // Via the semantics action, not performClick: in this fixed-size wrapper a laid-out but
    // off-screen node swallows a click without reporting a failure.
    private fun tapToolbarDelete() {
        composeTestRule.onAllNodesWithContentDescription(deleteLabel)
            .onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the toolbar delete asks before deleting`() {
        setDetail()

        tapToolbarDelete()

        composeTestRule.onNodeWithText(confirmTitle).assertIsDisplayed()
        coVerify(exactly = 0) { bugReportRepo.delete(any()) }

        composeTestRule.onNodeWithText(cancelLabel).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(confirmTitle).assertDoesNotExist()
        composeTestRule.onAllNodesWithContentDescription(backLabel).onFirst().assertExists()
        coVerify(exactly = 0) { bugReportRepo.delete(any()) }
    }

    @Test
    fun `confirming the toolbar delete removes the report`() {
        setDetail()

        tapToolbarDelete()

        composeTestRule.onNodeWithText(deleteLabel).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(confirmTitle).assertDoesNotExist()
        coVerify(exactly = 1) { bugReportRepo.delete(report.id) }
    }
}

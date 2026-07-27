package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.FakeWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import eu.darken.butler.workspace.R as WorkspaceR

/**
 * A tab is now released together with its opted-in overlays, so the full-screen modal path can be
 * handed a paused workspace. It must show the placeholder instead of the typed page host: there is no
 * instance behind a paused id, and the host's ViewModel would wait for one forever.
 *
 * Drives [WorkspaceModalContent] rather than [WorkspaceModalDialog]: the Dialog wrapper only
 * reconciles a platform window with the Activity's edge-to-edge setup, while the lifecycle gate under
 * test lives entirely in the content.
 *
 * The root is sized through Robolectric qualifiers, not a fixed-size wrapper - a wrapper larger than
 * the test root would push the resume button off it and make `performClick` silently miss.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h800dp")
class WorkspaceModalPausedContentTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Stands in for the app details page: records whether it was composed at all. */
    private class RecordingHost : WorkspacePageHostEntry {
        var contentComposed = false
        var overlaysComposed = false

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            contentComposed = true
            Box(modifier = Modifier.fillMaxSize().testTag(CONTENT_TAG))
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
            overlaysComposed = true
        }
    }

    private fun modalInfo(lifecycleState: Workspace.LifecycleState) = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.APP_DETAILS,
        title = TITLE.toCaString(),
        lifecycleState = lifecycleState,
        callerWorkspaceId = Workspace.Id(),
    )

    @Composable
    private fun Content(
        host: WorkspacePageHostEntry,
        workspace: Workspace.Info,
        onResume: () -> Unit = {},
    ) {
        PreviewWrapper {
            CompositionLocalProvider(
                LocalWorkspacePageHosts provides mapOf(Workspace.Type.APP_DETAILS to host),
                // The real screen always has one; the modal has to suppress it locally
                LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(),
            ) {
                WorkspaceModalContent(
                    workspace = workspace,
                    design = WorkspaceDesign(),
                    onResumeWorkspace = onResume,
                )
            }
        }
    }

    @Test
    fun `a paused modal shows the placeholder and never composes its page`() {
        val host = RecordingHost()

        composeTestRule.setContent {
            Content(host = host, workspace = modalInfo(Workspace.LifecycleState.Paused()))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            context.getString(WorkspaceR.string.workspace_paused_resume_action)
        ).assertIsDisplayed()
        host.contentComposed shouldBe false
        host.overlaysComposed shouldBe false
    }

    @Test
    fun `the placeholder's resume action reaches the caller`() {
        val host = RecordingHost()
        var resumed = 0

        composeTestRule.setContent {
            Content(
                host = host,
                workspace = modalInfo(Workspace.LifecycleState.Paused()),
                onResume = { resumed++ },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(
            context.getString(WorkspaceR.string.workspace_paused_resume_action)
        ).performClick()

        resumed shouldBe 1
    }

    @Test
    fun `a paused modal offers no way into the tab manager`() {
        val host = RecordingHost()

        composeTestRule.setContent {
            Content(host = host, workspace = modalInfo(Workspace.LifecycleState.Paused()))
        }
        composeTestRule.waitForIdle()

        // The manager would open behind the modal window
        composeTestRule.onAllNodesWithTag(WorkspaceButtonDefaults.TEST_TAG).fetchSemanticsNodes()
            .size shouldBe 0
    }

    @Test
    fun `a ready modal still composes its page`() {
        val host = RecordingHost()

        composeTestRule.setContent {
            Content(host = host, workspace = modalInfo(Workspace.LifecycleState.Ready))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()
        host.contentComposed shouldBe true
        host.overlaysComposed shouldBe true
    }

    companion object {
        private const val TITLE = "Butler"
        private const val CONTENT_TAG = "modal-page-content"
    }
}

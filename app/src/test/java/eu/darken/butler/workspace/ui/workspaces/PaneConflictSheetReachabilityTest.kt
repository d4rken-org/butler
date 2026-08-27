package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.height
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheetDefaults
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Instant

/**
 * The conflict sheet at the geometry it was reported broken on: one pane of a two-pane landscape
 * Pixel 6 (388dp × 411dp at 420dpi), driven through the real [WorkspacePane] rather than the sheet
 * on its own — the container chain is what an isolated component test assumes away.
 *
 * Both halves matter. `performScrollTo` proves each action can be *brought* on screen, and the swipe
 * proves a finger gets there too: a semantics scroll bypasses the gesture pipeline entirely, so on
 * its own it cannot tell a working sheet from one whose drags are being eaten.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w388dp-h411dp-420dpi")
class PaneConflictSheetReachabilityTest : ComposeTest() {

    private val paneEdges = WorkspaceDesign.PaneEdges(
        touchesTop = true,
        touchesBottom = true,
        touchesStart = true,
        touchesEnd = false,
    )

    private val design = WorkspaceDesign(paneEdges = paneEdges)

    private val paneInfo = WorkspacePaneInfo(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        lifecycleState = Workspace.LifecycleState.Ready,
        title = "Test".toCaString(),
    )

    /** Stands in for the explorer page: nothing but the conflict sheet in the overlay slot. */
    private object FakePageHost : WorkspacePageHostEntry {
        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
            val insets = design.paneInsets()
            IssuesBottomSheet(
                issue = CONFLICT,
                onResolution = {},
                onDismiss = {},
                topInset = insets.top,
                bottomInset = insets.bottom,
            )
        }
    }

    @Composable
    private fun Case() {
        CompositionLocalProvider(
            LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to FakePageHost),
        ) {
            WorkspacePane(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PANE_TAG),
                info = paneInfo,
                design = design,
                paneFocused = true,
                activeWorkspaceId = paneInfo.id,
                onRequestPaneFocus = {},
                managerDialogStates = emptyMap(),
                onScreenAction = {},
                bannerStates = emptyMap(),
                onDismissBanner = {},
                onShareError = { _, _ -> },
                onCloseWorkspace = {},
                onResumeWorkspace = {},
                paneEdges = paneEdges,
            )
        }
    }

    @Test
    fun `every action can be scrolled to in a landscape pane`() {
        composeTestRule.setContent { Case() }

        val pane = composeTestRule.onNodeWithTag(PANE_TAG).getUnclippedBoundsInRoot()
        ACTIONS.forEach { action ->
            composeTestRule.onNodeWithText(action).performScrollTo().assertIsDisplayed()
            val bounds = composeTestRule.onNodeWithText(action).getUnclippedBoundsInRoot()
            bounds.bottom shouldBeLessThanOrEqualTo pane.bottom
        }

        val card = composeTestRule.onNodeWithTag(PaneScopedBottomSheetDefaults.CARD_TEST_TAG)
            .getUnclippedBoundsInRoot()
        card.height shouldBeLessThanOrEqualTo pane.height
    }

    @Test
    fun `swiping reaches the last action in a landscape pane`() {
        composeTestRule.setContent { Case() }

        // Deliberately swipes rather than scrolling by semantics: this is the path a finger takes
        composeTestRule.onNodeWithTag(PaneScopedBottomSheetDefaults.CARD_TEST_TAG)
            .performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.2f) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(ACTIONS.last()).assertIsDisplayed()
    }

    companion object {
        private const val PANE_TAG = "pane.host"
        private val ACTIONS = listOf("Skip", "Replace", "Rename new", "Rename existing", "Cancel")

        private fun lookup(path: String) = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = FileType.FILE,
            size = 1024L,
            modifiedAt = Instant.fromEpochMilliseconds(0L),
            target = null,
        )

        private val CONFLICT = PathActionIssue.PathAlreadyExists(
            source = lookup("/storage/emulated/0/Desktop/report.pdf"),
            destination = lookup("/storage/emulated/0/Download/report.pdf"),
            canSkip = true,
            canOverwrite = true,
            canRenameSource = true,
            canRenameDestination = true,
        )
    }
}

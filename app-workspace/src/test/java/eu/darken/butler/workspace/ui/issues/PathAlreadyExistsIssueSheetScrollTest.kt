package eu.darken.butler.workspace.ui.issues

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.height
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheetDefaults
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Instant

/**
 * The reported bug, in the shape it was reported: a landscape pane is short enough that the
 * conflict sheet's actions end up below the viewport. They used to be clipped away with no way to
 * reach them — which is why the assertions here scroll to each action first. A `performClick` that
 * succeeds proves nothing about whether the control was ever on screen.
 *
 * The pane deliberately *is* the test root; a bottom-anchored sheet inside an oversized box would
 * hang off the end of the root and swallow every injected touch.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w731dp-h411dp")
class PathAlreadyExistsIssueSheetScrollTest : ComposeTest() {

    private fun lookup(path: String) = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Instant.fromEpochMilliseconds(0L),
        target = null,
    )

    private fun conflict(name: String) = PathActionIssue.PathAlreadyExists(
        source = lookup("/storage/emulated/0/Desktop/$name"),
        destination = lookup("/storage/emulated/0/Download/$name"),
        canSkip = true,
        canOverwrite = true,
        canRenameSource = true,
        canRenameDestination = true,
    )

    @Composable
    private fun Case(
        issue: PathActionIssue.PathAlreadyExists,
        onResolution: (PathActionIssue.Resolution) -> Unit = {},
    ) {
        PreviewWrapper {
            PaneLayerHost(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PANE_TAG),
                paneFocused = true,
            ) {
                IssuesBottomSheet(
                    issue = issue,
                    onResolution = onResolution,
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `every action is reachable in a landscape pane`() {
        val resolutions = mutableListOf<PathActionIssue.Resolution>()
        composeTestRule.setContent { Case(issue = conflict("report.pdf"), onResolution = { resolutions.add(it) }) }

        ACTIONS.forEach { action ->
            composeTestRule.onNodeWithText(action).performScrollTo().assertIsDisplayed()
        }

        val pane = composeTestRule.onNodeWithTag(PANE_TAG).getUnclippedBoundsInRoot()
        val card = composeTestRule.onNodeWithTag(PaneScopedBottomSheetDefaults.CARD_TEST_TAG)
            .getUnclippedBoundsInRoot()
        card.height shouldBeLessThanOrEqualTo pane.height
        card.bottom shouldBe pane.bottom

        // Reachable also means usable, not merely laid out
        composeTestRule.onNodeWithText(CANCEL_ACTION).performScrollTo().performClick()
        resolutions.single() shouldBe PathActionIssue.PathAlreadyExists.Resolution.Cancel()
    }

    /**
     * The rename dialog is a sibling of the sheet, so opening and dismissing it recomposes the sheet
     * without replacing its content. The place the user had scrolled to has to survive that.
     */
    @Test
    fun `opening the rename dialog does not reset the sheet scroll`() {
        var dispatcher: OnBackPressedDispatcher? = null
        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Case(issue = conflict("report.pdf"))
        }

        composeTestRule.onNodeWithText(CANCEL_ACTION).performScrollTo()
        val scrolledTo = composeTestRule.onNodeWithText(SKIP_ACTION).getUnclippedBoundsInRoot().top

        composeTestRule.onNodeWithText(RENAME_NEW_ACTION).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG).assertIsDisplayed()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(SKIP_ACTION).getUnclippedBoundsInRoot().top shouldBe scrolledTo
    }

    companion object {
        private const val PANE_TAG = "pane.host"

        /** `workspace_issue_common_*` / `workspace_issue_collision_overwrite`. */
        private const val SKIP_ACTION = "Skip"
        private const val REPLACE_ACTION = "Replace"
        private const val RENAME_NEW_ACTION = "Rename new"
        private const val RENAME_EXISTING_ACTION = "Rename existing"
        private const val CANCEL_ACTION = "Cancel"

        private val ACTIONS = listOf(
            SKIP_ACTION,
            REPLACE_ACTION,
            RENAME_NEW_ACTION,
            RENAME_EXISTING_ACTION,
            CANCEL_ACTION,
        )
    }
}

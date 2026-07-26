package eu.darken.butler.workspace.ui.issues

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.modal.LocalPaneLayerRank
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Instant

/**
 * The rename dialog is emitted by [IssuesBottomSheet] as a sibling of the sheet, never inside it —
 * inside it would be clipped to the sheet's bounds and stacked below it. As a sibling it must end
 * up on top, hand the sheet back on dismissal, and never let the pane content underneath become
 * reachable in between.
 *
 * The screen qualifiers are load-bearing, not decoration. The issue sheet is taller than the
 * default test root, and it anchors to the bottom of its pane — on a short root its action buttons
 * end up past the bottom edge, where an injected touch hits nothing while the click call still
 * reports success. Every failure then looks like "the dialog never opened".
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h800dp")
class PathIssueRenameStackingTest : ComposeTest() {

    private val surface = PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG

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
        onSheetDismiss: () -> Unit = {},
        rank: Int = PaneLayerRank.OVERLAY,
        onContentActive: (Boolean) -> Unit = {},
    ) {
        PreviewWrapper {
            // The pane deliberately *is* the test root rather than a fixed-size box inside it. A
            // box larger than the root would hang the sheet's bottom edge off the end of the root,
            // and an injected touch aimed at a button down there lands on nothing while
            // performClick still reports success — the failure then surfaces as a missing dialog.
            PaneLayerHost(
                modifier = Modifier.fillMaxSize().testTag(PANE_TAG),
                paneFocused = true,
                paneEdges = PANE_EDGES,
            ) {
                PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                    onContentActive(LocalLayerActive.current)
                }
                CompositionLocalProvider(LocalPaneLayerRank provides rank) {
                    IssuesBottomSheet(
                        issue = issue,
                        onResolution = onResolution,
                        onDismiss = onSheetDismiss,
                    )
                }
            }
        }
    }

    /**
     * Splits the two failure modes the integration cases below cannot tell apart: if this passes
     * while they fail, the hoist or the dialog's rendering is at fault; if this fails too, the
     * sheet's own button wiring is. It also pins the other half of the hoist — the sheet must no
     * longer own a dialog of its own.
     */
    @Test
    fun `the sheet emits a rename request instead of opening its own dialog`() {
        val issue = conflict("report.pdf")
        val requests = mutableListOf<PathIssueRenameRequest>()

        composeTestRule.setContent {
            PreviewWrapper {
                PathAlreadyExistsIssueSheet(
                    issue = issue,
                    onResolution = {},
                    onRenameRequest = { requests.add(it) },
                )
            }
        }

        composeTestRule.onNodeWithText(RENAME_NEW_ACTION).performClick()
        composeTestRule.onNodeWithText(RENAME_EXISTING_ACTION).performClick()

        composeTestRule.runOnIdle {
            requests shouldBe listOf(
                PathIssueRenameRequest(
                    issueId = issue.id,
                    target = PathIssueRenameRequest.Target.SOURCE,
                    currentName = "report.pdf",
                ),
                PathIssueRenameRequest(
                    issueId = issue.id,
                    target = PathIssueRenameRequest.Target.DESTINATION,
                    currentName = "report.pdf",
                ),
            )
        }
        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
    }

    @Test
    fun `back dismisses the rename dialog first and the sheet second`() {
        var sheetDismissals = 0
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Case(issue = conflict("report.pdf"), onSheetDismiss = { sheetDismissals++ })
        }

        composeTestRule.onNodeWithText(RENAME_NEW_ACTION).performClick()
        composeTestRule.onNodeWithTag(surface).assertExists()

        // Composed after the sheet rather than inside it, so its scrim covers the whole pane. Moved
        // inside, the scrim would be clamped to the sheet card's bounds — this is the assertion that
        // actually distinguishes the hoist from the arrangement it replaced.
        assertScrimSpansPane()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        // First back belongs to the dialog: it closes, the sheet stays and is untouched
        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
        composeTestRule.runOnIdle { sheetDismissals shouldBe 0 }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.runOnIdle { sheetDismissals shouldBe 1 }
    }

    private fun assertScrimSpansPane() {
        val pane = composeTestRule.onNodeWithTag(PANE_TAG).getUnclippedBoundsInRoot()
        val scrim = composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SCRIM_TEST_TAG)
            .getUnclippedBoundsInRoot()
        scrim.width shouldBe pane.width
        scrim.height shouldBe pane.height
    }

    /**
     * `LocalLayerActive` alone does not prove focus went anywhere — the pane content layer must
     * actually be inactive the whole time, so nothing behind the sheet can take keyboard focus back
     * while the dialog is up.
     */
    @Test
    fun `the pane content stays inactive while the rename dialog is open`() {
        var contentActive: Boolean? = null

        composeTestRule.setContent {
            Case(issue = conflict("report.pdf"), onContentActive = { contentActive = it })
        }

        composeTestRule.runOnIdle { contentActive shouldBe false }

        composeTestRule.onNodeWithText(RENAME_NEW_ACTION).performClick()
        composeTestRule.runOnIdle { contentActive shouldBe false }

        // The dialog owns keyboard focus, so its field is the one that accepts input
        composeTestRule.onNode(hasSetTextAction()).assertExists()
    }

    @Test
    fun `confirming the rename resolves the conflict the dialog was opened for`() {
        val resolutions = mutableListOf<PathActionIssue.Resolution>()

        composeTestRule.setContent {
            Case(issue = conflict("report.pdf"), onResolution = { resolutions.add(it) })
        }

        composeTestRule.onNodeWithText(RENAME_EXISTING_ACTION).performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("renamed.pdf")
        composeTestRule.onNodeWithText(RENAME_CONFIRM_ACTION).performClick()

        composeTestRule.runOnIdle {
            resolutions shouldBe listOf(
                PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("renamed.pdf", applyToAll = false),
            )
        }
    }

    /**
     * A conflict that is replaced while the dialog is open must take the request with it, otherwise
     * confirming would resolve the new conflict with the previous one's name.
     */
    @Test
    fun `a replaced conflict drops the pending rename`() {
        var issue by mutableStateOf(conflict("first.pdf"))

        composeTestRule.setContent { Case(issue = issue) }

        composeTestRule.onNodeWithText(RENAME_NEW_ACTION).performClick()
        composeTestRule.onNodeWithTag(surface).assertExists()

        composeTestRule.runOnIdle { issue = conflict("second.pdf") }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
    }

    /**
     * The saver runs as a pane-local child, whose overlays sit at the child rank. The dialog has to
     * inherit that rank from the sheet rather than hardcode the parent's [PaneLayerRank.OVERLAY].
     *
     * Bounds cannot show this: a layer renders in place at whatever rank it holds, so a hardcoded
     * parent rank would still produce a full-pane scrim here. What changes is which layer is *on
     * top* — ranked below the sheet, the dialog would not own back, and the first press would take
     * the sheet down from underneath it. So back ordering is the assertion that discriminates.
     */
    @Test
    fun `the dialog inherits the ambient rank instead of the parent overlay rank`() {
        var sheetDismissals = 0
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Case(
                issue = conflict("report.pdf"),
                onSheetDismiss = { sheetDismissals++ },
                rank = PaneLayerRank.CHILD_OVERLAY,
            )
        }

        composeTestRule.onNodeWithText(RENAME_NEW_ACTION).performClick()
        composeTestRule.onNodeWithTag(surface).assertExists()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
        composeTestRule.runOnIdle { sheetDismissals shouldBe 0 }

        // ...and the scrim still covers the whole pane, not just the inset content area
        composeTestRule.onNodeWithText(RENAME_NEW_ACTION).performClick()
        assertScrimSpansPane()
    }

    companion object {
        private const val PANE_TAG = "pane.host"

        /** `workspace_issue_common_rename_new` / `_existing` / `general_rename_action`. */
        private const val RENAME_NEW_ACTION = "Rename new"
        private const val RENAME_EXISTING_ACTION = "Rename existing"
        private const val RENAME_CONFIRM_ACTION = "Rename"

        private val PANE_EDGES = WorkspaceDesign.PaneEdges(
            touchesTop = false,
            touchesBottom = false,
            touchesStart = true,
            touchesEnd = false,
        )
    }
}

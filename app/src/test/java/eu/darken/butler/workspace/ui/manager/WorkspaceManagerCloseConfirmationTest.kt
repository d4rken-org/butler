package eu.darken.butler.workspace.ui.manager

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.workspaces.routeManagerDialogs
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import eu.darken.butler.common.R as CommonR

/**
 * The manager overlay covers every pane, so it is what hosts a close confirmation while it is up.
 */
@Config(qualifiers = "w400dp-h800dp")
class WorkspaceManagerCloseConfirmationTest : ComposeTest() {

    private val tabA = Workspace.Id()
    private val tabB = Workspace.Id()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun unsavedMessage(title: String) =
        context.getString(CommonR.string.general_tab_close_unsaved_message, title)

    private val unsavedTitle: String get() = context.getString(CommonR.string.general_tab_close_unsaved_title)

    private fun item(id: Workspace.Id, title: String) = WorkspaceManagerViewModel.WorkspaceItem(
        id = id,
        topId = id,
        type = Workspace.Type.EDITOR,
        title = title.toCaString(),
        autoTitle = title.toCaString(),
        subtitle = null,
    )

    private fun confirmation(id: String, closing: Workspace.Id, title: String) =
        ManagerDialog.WorkspaceTargeted.CloseConfirmation(
            id = id,
            targetWorkspaceId = closing,
            closingWorkspaceId = closing,
            workspaceTitle = title.toCaString(),
            hasUnsavedChanges = true,
        )

    private class Answers {
        val resolved = mutableListOf<Boolean>()
        var goToCount = 0
    }

    private fun compose(
        confirmations: List<ManagerDialog>,
        answers: Answers = Answers(),
    ): Answers {
        val routing = routeManagerDialogs(
            dialogs = confirmations,
            isManagerOverlayVisible = true,
            tabOrder = listOf(tabA, tabB),
        )

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceManagerScreen(
                    state = WorkspaceManagerViewModel.State(
                        workspaces = listOf(item(tabA, "Tab one"), item(tabB, "Tab two")),
                        // Card thumbnails load through Coil, which has nothing to serve here.
                        useLivePreview = false,
                        showBadgeExplanation = false,
                    ),
                    closeConfirmation = routing.managerHosted,
                    onCloseConfirmationResolve = { answers.resolved += it },
                    onCloseConfirmationGoTo = { answers.goToCount++ },
                    onCloseWorkspace = {},
                    onReorderWorkspaces = {},
                    onSelectWorkspace = {},
                    onPauseWorkspace = {},
                    onResumeWorkspace = {},
                    onCreateWorkspace = {},
                    onQuickCreate = {},
                    onNavigateBack = {},
                    onDismissBadgeExplanation = {},
                    onCloseAllWorkspaces = {},
                )
            }
        }
        return answers
    }

    @Test
    fun `two pending confirmations put exactly one dialog on screen`() {
        compose(
            confirmations = listOf(
                confirmation("c2", closing = tabB, title = "draft.md"),
                confirmation("c1", closing = tabA, title = "notes.txt"),
            ),
        )

        composeTestRule.onAllNodesWithText(unsavedTitle).assertCountEquals(1)
        composeTestRule.onNodeWithText(unsavedMessage("notes.txt")).assertExists()
        composeTestRule.onNodeWithText(unsavedMessage("draft.md")).assertDoesNotExist()
    }

    @Test
    fun `discarding resolves the confirmation as confirmed`() {
        val answers = compose(listOf(confirmation("c1", closing = tabA, title = "notes.txt")))

        composeTestRule.onNodeWithText(context.getString(CommonR.string.general_discard_action)).performClick()

        answers.resolved shouldBe listOf(true)
        answers.goToCount shouldBe 0
    }

    @Test
    fun `the manager always offers the way to the tab`() {
        val answers = compose(listOf(confirmation("c1", closing = tabA, title = "notes.txt")))

        composeTestRule
            .onNodeWithText(context.getString(CommonR.string.general_tab_close_goto_action))
            .performClick()

        // No pane hosts this dialog, so the jump is the only route to the tab it names.
        answers.goToCount shouldBe 1
        answers.resolved shouldBe emptyList()
    }
}

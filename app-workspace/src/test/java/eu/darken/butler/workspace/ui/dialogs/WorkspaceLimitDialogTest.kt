package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceLimitCandidate
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class WorkspaceLimitDialogTest : ComposeTest() {

    private val downloads = candidate("Downloads", Workspace.Type.EXPLORER)
    private val logs = candidate("Logs", Workspace.Type.SEARCHER)
    private val notes = candidate(
        title = "Notes",
        type = Workspace.Type.EDITOR,
        blocker = WorkspaceLimitCandidate.Blocker.UNSAVED_CHANGES,
    )

    private fun candidate(
        title: String,
        type: Workspace.Type,
        blocker: WorkspaceLimitCandidate.Blocker? = null,
    ) = WorkspaceLimitCandidate(
        id = Workspace.Id(),
        type = type,
        title = title.toCaString(),
        subtitle = null,
        openedAt = Clock.System.now() - 2.hours,
        blocker = blocker,
    )

    private fun setDialog(
        candidates: List<WorkspaceLimitCandidate> = listOf(downloads, logs, notes),
        canRecover: Boolean = true,
        minToClose: Int = 1,
        onCloseSelected: (Set<Workspace.Id>) -> Unit = {},
        onUpgrade: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceLimitDialog(
                    limit = 5,
                    onDismiss = onDismiss,
                    onUpgrade = onUpgrade,
                    candidates = candidates,
                    canRecover = canRecover,
                    minToClose = minToClose,
                    onCloseSelected = onCloseSelected,
                )
            }
        }
    }

    private fun confirmButton() =
        composeTestRule.onNodeWithTag(WorkspaceLimitDialogDefaults.CONFIRM_TEST_TAG)

    @Test
    fun `nothing is pre-selected, so closing tabs starts unavailable`() {
        setDialog()

        confirmButton().assertIsNotEnabled()
    }

    /** Counting the empty selection would render "Close 0 tabs", which reads as a broken action. */
    @Test
    fun `with nothing picked the confirm action names the minimum, not zero`() {
        setDialog(minToClose = 2)

        composeTestRule.onNodeWithText("Close 2 tabs").assertExists()
        composeTestRule.onNodeWithText("Close 0 tabs").assertDoesNotExist()
    }

    @Test
    fun `picking a tab arms the confirm action and reports that tab`() {
        var closed: Set<Workspace.Id>? = null
        setDialog(onCloseSelected = { closed = it })

        composeTestRule.onNodeWithText("Downloads").performClick()
        confirmButton().assertIsEnabled()
        confirmButton().performClick()

        closed shouldBe setOf(downloads.id)
    }

    @Test
    fun `several tabs can go in one confirm`() {
        var closed: Set<Workspace.Id>? = null
        setDialog(onCloseSelected = { closed = it })

        composeTestRule.onNodeWithText("Downloads").performClick()
        composeTestRule.onNodeWithText("Logs").performClick()
        confirmButton().performClick()

        closed shouldBe setOf(downloads.id, logs.id)
    }

    @Test
    fun `picking a tab twice unpicks it`() {
        setDialog()

        composeTestRule.onNodeWithText("Downloads").performClick()
        confirmButton().assertIsEnabled()
        composeTestRule.onNodeWithText("Downloads").performClick()

        confirmButton().assertIsNotEnabled()
    }

    @Test
    fun `a blocked tab shows its reason and cannot be picked`() {
        setDialog()

        composeTestRule.onNodeWithText("Unsaved changes").assertExists()
        composeTestRule.onNodeWithText("Notes").performClick()

        confirmButton().assertIsNotEnabled()
    }

    /** A restore overshoot can need more than one tab gone; a short selection must not arm confirm. */
    @Test
    fun `a higher minimum keeps confirm disabled until enough tabs are picked`() {
        setDialog(minToClose = 2)

        composeTestRule.onNodeWithText("Downloads").performClick()
        confirmButton().assertIsNotEnabled()

        composeTestRule.onNodeWithText("Logs").performClick()
        confirmButton().assertIsEnabled()
    }

    @Test
    fun `without a recovery the tabs are listed but confirm offers the upgrade`() {
        var upgraded = false
        setDialog(canRecover = false, onUpgrade = { upgraded = true })

        // Still listed: the user should see what is holding the slots
        composeTestRule.onNodeWithText("Downloads").assertExists()
        composeTestRule.onNodeWithText("Closing tabs won't free up a slot right now.").assertExists()

        confirmButton().performClick()
        upgraded shouldBe true
    }

    @Test
    fun `with nothing to replay it stays the plain notice it always was`() {
        var upgraded = false
        setDialog(candidates = emptyList(), canRecover = false, onUpgrade = { upgraded = true })

        composeTestRule.onNodeWithTag(WorkspaceLimitDialogDefaults.TAB_LIST_TEST_TAG).assertDoesNotExist()

        confirmButton().performClick()
        upgraded shouldBe true
    }
}

package eu.darken.butler.workspace.ui.manager

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The shortcuts the button used to hide behind a long press are its only content now, so what the
 * tap reveals - and what a row does when tapped - is the whole of the button's behaviour.
 */
class WorkspaceManagerFABTest : ComposeTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val triggerLabel: String get() = context.getString(R.string.workspace_fab_quick_shortcuts)
    private val closeLabel: String get() = context.getString(R.string.workspace_fab_close_shortcuts)
    private val closeAllLabel: String get() = context.getString(R.string.workspace_fab_close_all)

    private fun quickItem(type: Workspace.Type, title: String) = QuickCreateItem(
        type = type,
        icon = Icons.TwoTone.Add,
        title = title.toCaString(),
        arguments = type.defaultArguments!!,
    )

    private val explorer = quickItem(Workspace.Type.EXPLORER, "Explorer")
    private val searcher = quickItem(Workspace.Type.SEARCHER, "Searcher")

    private class Answers {
        val created = mutableListOf<QuickCreateItem>()
        var closeAllRequests = 0
    }

    private fun compose(
        workspaceCount: Int = 3,
        quickCreateItems: List<QuickCreateItem> = listOf(explorer, searcher),
    ): Answers {
        val answers = Answers()
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceManagerFAB(
                    workspaceCount = workspaceCount,
                    quickCreateItems = quickCreateItems,
                    onQuickCreate = { answers.created += it },
                    onShowCloseAllDialog = { answers.closeAllRequests++ },
                )
            }
        }
        return answers
    }

    private fun toggle() = composeTestRule
        .onNode(hasContentDescription(triggerLabel) or hasContentDescription(closeLabel))
        .performClick()

    @Test
    fun `a tap reveals the shortcuts and the close-all action`() {
        compose()

        composeTestRule.onNodeWithText("Explorer").assertDoesNotExist()

        toggle()

        composeTestRule.onNodeWithText("Explorer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Searcher").assertIsDisplayed()
        composeTestRule.onNodeWithText(closeAllLabel).assertIsDisplayed()
    }

    @Test
    fun `close-all is absent while a single tab is open`() {
        compose(workspaceCount = 1)

        toggle()

        composeTestRule.onNodeWithText("Explorer").assertIsDisplayed()
        composeTestRule.onNodeWithText(closeAllLabel).assertDoesNotExist()
    }

    @Test
    fun `a second tap collapses the stack`() {
        compose()

        toggle()
        toggle()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Explorer").assertDoesNotExist()
    }

    @Test
    fun `a shortcut creates its workspace and closes the stack`() {
        val answers = compose()

        toggle()
        composeTestRule.onNodeWithText("Searcher").performClick()
        composeTestRule.waitForIdle()

        answers.created shouldBe listOf(searcher)
        composeTestRule.onNodeWithText("Searcher").assertDoesNotExist()
    }

    @Test
    fun `the stack scrolls when it overflows`() {
        compose()

        toggle()
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasScrollAction() and hasAnyDescendant(hasText("Explorer"))).assertExists()
    }

    @Test
    fun `expanding hides the label and renames the button`() {
        compose()

        toggle()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(triggerLabel).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(closeLabel).assertExists()

        toggle()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(triggerLabel).assertExists()
    }

    @Test
    fun `close all asks for the confirmation instead of closing`() {
        val answers = compose()

        toggle()
        composeTestRule.onNodeWithText(closeAllLabel).performClick()

        answers.closeAllRequests shouldBe 1
    }
}

package eu.darken.butler.workspace.ui.actions

import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.Star
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.LocalTooltipsEnabled
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class WorkspaceActionBarTest : ComposeTest() {

    private data class SampleAction(
        override val icon: ImageVector,
        val name: String,
        override val isEnabled: Boolean = true,
        override val isDestructive: Boolean = false,
        override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
        override val forceOverflow: Boolean = false,
    ) : WorkspaceActionBarItem {
        override val label: CaString = name.toCaString()
    }

    private val share = SampleAction(Icons.TwoTone.Share, "Share")
    private val edit = SampleAction(Icons.TwoTone.Edit, "Edit", isEnabled = false)
    private val delete = SampleAction(Icons.TwoTone.Delete, "Delete", isDestructive = true)
    private val butlerCopy = SampleAction(Icons.TwoTone.ContentCopy, "Copy to Butler", forceOverflow = true)

    private val overflowLabel = "More actions"

    private fun setBar(
        actions: List<WorkspaceActionBarItem>,
        width: Int? = null,
        tooltipsEnabled: Boolean = true,
        onActionClick: (WorkspaceActionBarItem) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalTooltipsEnabled provides tooltipsEnabled) {
                    WorkspaceActionBar(
                        modifier = if (width != null) Modifier.width(width.dp) else Modifier,
                        actions = actions,
                        onActionClick = onActionClick,
                    )
                }
            }
        }
    }

    @Test
    fun `every displayed action shows its label as a tooltip on long-press`() {
        setBar(listOf(share, edit, delete))

        listOf(share, edit, delete).forEach { action ->
            composeTestRule.onNodeWithContentDescription(action.name).performTouchInput { longClick() }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText(action.name).assertIsDisplayed()
        }
    }

    /** An unfocused pane turns tooltips off, and the bar has to honour that like every other one. */
    @Test
    fun `no tooltip appears while tooltips are disabled`() {
        setBar(listOf(share), tooltipsEnabled = false)

        composeTestRule.onNodeWithContentDescription("Share").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Share").assertCountEquals(0)
    }

    @Test
    fun `a tap invokes the click callback exactly once`() {
        val clicks = mutableListOf<WorkspaceActionBarItem>()
        setBar(listOf(share, delete), onActionClick = { clicks.add(it) })

        composeTestRule.onNodeWithContentDescription("Share").performClick()

        clicks shouldBe listOf(share)
    }

    @Test
    fun `a long-press does not invoke the click callback`() {
        val clicks = mutableListOf<WorkspaceActionBarItem>()
        setBar(listOf(share, delete), onActionClick = { clicks.add(it) })

        composeTestRule.onNodeWithContentDescription("Share").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        clicks shouldBe emptyList()
    }

    @Test
    fun `a disabled action shows its tooltip but does not invoke the click callback`() {
        val clicks = mutableListOf<WorkspaceActionBarItem>()
        setBar(listOf(share, edit, delete), onActionClick = { clicks.add(it) })

        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        clicks shouldBe emptyList()

        composeTestRule.onNodeWithContentDescription("Edit").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
        clicks shouldBe emptyList()
    }

    @Test
    fun `a forced action stays out of the bar and appears in the overflow menu`() {
        setBar(listOf(share, edit, delete, butlerCopy))

        // Everything else fits, so only the forced action drives the overflow button
        listOf(share, edit, delete).forEach {
            composeTestRule.onNodeWithContentDescription(it.name).assertIsDisplayed()
        }
        composeTestRule.onAllNodesWithContentDescription("Copy to Butler").assertCountEquals(0)

        composeTestRule.onNodeWithContentDescription(overflowLabel).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Copy to Butler").assertIsDisplayed()
    }

    @Test
    fun `forced and naturally overflowing actions each appear exactly once`() {
        val actions = listOf(
            share,
            edit,
            delete,
            SampleAction(Icons.TwoTone.ContentCopy, "Copy"),
            SampleAction(Icons.TwoTone.Star, "Favorite", group = WorkspaceActionBarItem.Group.SECONDARY),
            SampleAction(Icons.TwoTone.Refresh, "Refresh", group = WorkspaceActionBarItem.Group.SECONDARY),
            butlerCopy,
        )
        setBar(actions, width = 160)

        composeTestRule.onNodeWithContentDescription(overflowLabel).performClick()
        composeTestRule.waitForIdle()

        actions.forEach { action ->
            val name = (action as SampleAction).name
            val inBar = composeTestRule.onAllNodesWithContentDescription(name).fetchSemanticsNodes().size
            val inMenu = composeTestRule.onAllNodesWithText(name).fetchSemanticsNodes().size
            withClue(name) { inBar + inMenu shouldBe 1 }
        }
    }
}

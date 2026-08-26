package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.modal.suppressPressesUnless
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The trailing creation placeholder carries the press gate itself.
 *
 * It is composed outside `WorkspacePane`, so it inherits nothing from the pane contract, and a tap
 * on the sliver of it that shows mid-swipe creates a blank workspace — a worse outcome than the
 * focus bounce the pane gate prevents, and one that invalidates whatever the user was doing.
 */
class PlaceholderPressGateTest : ComposeTest() {

    private val tabId = Workspace.Id()

    @Test
    fun `a tap creates nothing while the pager is not resting on the placeholder`() {
        var created = 0

        composeTestRule.setContent {
            PreviewWrapper { Placeholder(resting = false, onCreate = { created++ }) }
        }

        composeTestRule.onNodeWithTag(PLACEHOLDER_TAG).performTouchInput {
            down(Offset(4f, 4f))
            up()
        }
        composeTestRule.waitForIdle()

        created shouldBe 0
    }

    /**
     * Why the gate sits on the down rather than around the click callback: `clickable` decides on
     * the up. A finger that lands on the placeholder mid-swipe, stays put through the settle and is
     * lifted afterwards would find the gate open by then.
     */
    @Test
    fun `a tap held from before the settle until after it still creates nothing`() {
        var resting by mutableStateOf(false)
        var created = 0

        composeTestRule.setContent {
            PreviewWrapper { Placeholder(resting = resting, onCreate = { created++ }) }
        }

        composeTestRule.onNodeWithTag(PLACEHOLDER_TAG).performTouchInput { down(Offset(4f, 4f)) }
        composeTestRule.runOnIdle { resting = true }
        composeTestRule.onNodeWithTag(PLACEHOLDER_TAG).performTouchInput { up() }
        composeTestRule.waitForIdle()

        created shouldBe 0
    }

    /**
     * The control: the same tap on the same target creates once the pager rests there, so the two
     * cases above are about the gate and not about the tap never having reached anything.
     */
    @Test
    fun `the same tap creates once the pager rests on the placeholder`() {
        var created = 0

        composeTestRule.setContent {
            PreviewWrapper { Placeholder(resting = true, onCreate = { created++ }) }
        }

        composeTestRule.onNodeWithTag(PLACEHOLDER_TAG).performTouchInput {
            down(Offset(4f, 4f))
            up()
        }
        composeTestRule.waitForIdle()

        created shouldBe 1
    }

    /** The placeholder page as the classic container composes it: real controller, real gate. */
    @Composable
    private fun Placeholder(resting: Boolean, onCreate: () -> Unit) {
        val controller = rememberPlaceholderCreationController(
            // One page, and the placeholder is not it: auto-creation must stay out of this.
            pagerState = rememberPagerState(pageCount = { 1 }),
            tabIds = listOf(tabId),
            onDemandEnabled = true,
            isInteractionBlocked = false,
            hasBlockingDialog = false,
            onCreateRequested = onCreate,
        )
        CreatingWorkspacePlaceholder(
            modifier = Modifier
                .suppressPressesUnless { resting }
                .testTag(PLACEHOLDER_TAG),
            onClick = { controller.onPlaceholderClick() },
        )
    }

    companion object {
        private const val PLACEHOLDER_TAG = "classic.placeholder"
    }
}

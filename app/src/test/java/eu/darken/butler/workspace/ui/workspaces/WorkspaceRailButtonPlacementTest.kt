package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.layout.RailButtonPlacement
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.RailPlacement
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRail
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import kotlin.math.abs

/**
 * Which end of the rail the Butler button takes. The tab list is the rail's only weighted child, so
 * both the order the two are emitted in and the button's own distance to the rail's far edge are
 * asserted: a button that merely follows a list too short to fill the rail would pass the order
 * check while sitting in the middle of the rail, which is not the corner the setting promises.
 */
@Config(qualifiers = "w411dp-h891dp")
class WorkspaceRailButtonPlacementTest : ComposeTest() {

    @Test
    fun `a leading button sits at the start of a bottom rail`() {
        setRail(placement = RailPlacement.BOTTOM, buttonPlacement = RailButtonPlacement.LEADING)

        withClue("The button should precede the tab list") {
            (button().right <= list().left) shouldBe true
        }
        assertAtEdge(button().left - content().left)
    }

    @Test
    fun `a trailing button sits at the end of a bottom rail`() {
        setRail(placement = RailPlacement.BOTTOM, buttonPlacement = RailButtonPlacement.TRAILING)

        withClue("The button should follow the tab list") {
            (button().left >= list().right) shouldBe true
        }
        assertAtEdge(content().right - button().right)
    }

    @Test
    fun `a trailing button follows the layout direction`() {
        setRail(
            placement = RailPlacement.BOTTOM,
            buttonPlacement = RailButtonPlacement.TRAILING,
            layoutDirection = LayoutDirection.Rtl,
        )

        withClue("The end of the rail is its left side in a right to left layout") {
            (button().right <= list().left) shouldBe true
        }
        assertAtEdge(button().left - content().left)
    }

    @Test
    fun `a trailing button sits at the bottom of a start rail`() {
        setRail(placement = RailPlacement.START, buttonPlacement = RailButtonPlacement.TRAILING)

        withClue("The button should follow the tab list") {
            (button().top >= list().bottom) shouldBe true
        }
        assertAtEdge(content().bottom - button().bottom)
    }

    @Test
    fun `a trailing button reaches the far edge without any tabs`() {
        setRail(
            placement = RailPlacement.BOTTOM,
            buttonPlacement = RailButtonPlacement.TRAILING,
            workspaces = emptyList(),
        )

        assertAtEdge(content().right - button().right)
    }

    private fun button() = composeTestRule
        .onNodeWithTag(WorkspaceButtonDefaults.TEST_TAG)
        .getUnclippedBoundsInRoot()

    private fun list() = composeTestRule
        .onNodeWithTag(WorkspaceNavigationRailDefaults.LIST_TEST_TAG)
        .getUnclippedBoundsInRoot()

    private fun content() = composeTestRule
        .onNodeWithTag(WorkspaceNavigationRailDefaults.CONTENT_TEST_TAG)
        .getUnclippedBoundsInRoot()

    private fun assertAtEdge(gap: Dp) = withClue("The button is $gap from the rail's far edge") {
        (abs(gap.value) <= EDGE_TOLERANCE.value) shouldBe true
    }

    private fun setRail(
        placement: RailPlacement,
        buttonPlacement: RailButtonPlacement,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        workspaces: List<Workspace.Info> = tabs,
    ) {
        // The mascot on the Butler button animates on an endless loop, which never lets the clock idle.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        WorkspaceNavigationRail(
                            modifier = Modifier.align(
                                when (placement) {
                                    RailPlacement.START -> Alignment.CenterStart
                                    RailPlacement.BOTTOM -> Alignment.BottomCenter
                                },
                            ),
                            workspaces = workspaces,
                            selected = emptyMap(),
                            focusedId = workspaces.firstOrNull()?.id,
                            design = WorkspaceDesign(
                                layout = WorkspaceDesign.Layout.DUAL_HORIZONTAL,
                                railPlacement = placement,
                                railButtonPlacement = buttonPlacement,
                            ),
                            onTabAction = {},
                            onPaneAssignment = { _, _ -> },
                            onPaneUnassign = {},
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private val tabs = (0 until 3).map {
        Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            title = "Explorer $it".toCaString(),
            lifecycleState = Workspace.LifecycleState.Ready,
        )
    }

    companion object {
        /** What the rail's own section padding and content inset leave between button and edge. */
        private val EDGE_TOLERANCE = 16.dp
    }
}

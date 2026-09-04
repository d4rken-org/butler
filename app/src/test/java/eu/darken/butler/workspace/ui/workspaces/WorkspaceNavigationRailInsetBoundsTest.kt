package eu.darken.butler.workspace.ui.workspaces

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.RailPlacement
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceRailContainer
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The rail is the one surface that touches the status bar, the gesture bar and the side cutout at
 * once. Its tonal background has to be painted across all of them, so the window insets belong on
 * the content column inside the surface, never on the surface itself: inset the surface and the
 * strips beside the rail fall back to the window background.
 *
 * The insets stay inside the surface, but they must not disappear from the rail's footprint either
 * — the pane next to it still has to start after the inset plus the full 80dp rail, and the content
 * must keep those 80dp instead of being squeezed into 80dp including the inset. Both placements are
 * covered: they claim different sides, and the bottom one keeps its 80dp as a minimum rather than a
 * fixed size.
 *
 * This drives [WorkspaceRailContainer] rather than the full rail: the rail's header renders the
 * Lottie-backed mascot, which Robolectric cannot draw, while the container holds all of the
 * geometry under test.
 *
 * The environment supplies no window insets of its own, so fixed ones are dispatched to the compose
 * view; without them every bound is identical and nothing can be told apart.
 */
class WorkspaceNavigationRailInsetBoundsTest : ComposeTest() {

    private fun renderRail(
        layoutDirection: LayoutDirection,
        placement: RailPlacement = RailPlacement.START,
    ): Density {
        var view: View? = null
        lateinit var density: Density

        composeTestRule.setContent {
            view = LocalView.current
            density = LocalDensity.current
            // No theme wrapper on purpose: Material's defaults are enough for the geometry under
            // test, and this test shares a JVM with the rest of the module's suite.
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                when (placement) {
                    RailPlacement.START -> Row(
                        modifier = Modifier
                            .size(width = 300.dp, height = 500.dp)
                            .testTag(ROOT_TAG),
                    ) {
                        WorkspaceRailContainer(placement = placement) {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                        // Stands in for the pane container beside the rail
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(SIBLING_TAG),
                        )
                    }
                    RailPlacement.BOTTOM -> Column(
                        modifier = Modifier
                            .size(width = 300.dp, height = 500.dp)
                            .testTag(ROOT_TAG),
                    ) {
                        // Stands in for the pane container above the rail
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag(SIBLING_TAG),
                        )
                        WorkspaceRailContainer(placement = placement) { listModifier ->
                            // An entry-sized child rather than a filling one: the rail is the
                            // unweighted child here, so a child that fills would claim the window.
                            Box(modifier = listModifier.height(ENTRY_HEIGHT))
                        }
                    }
                }
            }
        }

        composeTestRule.runOnUiThread {
            val bars = when (layoutDirection) {
                LayoutDirection.Ltr -> Insets.of(SIDE_INSET_PX, TOP_INSET_PX, 0, BOTTOM_INSET_PX)
                LayoutDirection.Rtl -> Insets.of(0, TOP_INSET_PX, SIDE_INSET_PX, BOTTOM_INSET_PX)
            }
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        composeTestRule.waitForIdle()

        return density
    }

    @Test
    fun `the rail surface spans the full height while its content is inset`() {
        val density = renderRail(LayoutDirection.Ltr)
        val sideInset = with(density) { SIDE_INSET_PX.toDp() }

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val surface = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.CONTENT_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val sibling = composeTestRule.onNodeWithTag(SIBLING_TAG).getUnclippedBoundsInRoot()

        // The painted surface reaches the status bar, the gesture bar and the side cutout
        surface.left shouldBe root.left
        surface.top shouldBe root.top
        surface.bottom shouldBe root.bottom

        // ...while what the user touches is moved clear of them
        (content.left > root.left) shouldBe true
        (content.top > root.top) shouldBe true
        (content.bottom < root.bottom) shouldBe true

        // ...without being squeezed: the inset is added to the rail, not taken out of it
        (content.right - content.left) shouldBe RAIL_THICKNESS
        (content.left - root.left) shouldBe sideInset

        // ...so the pane beside the rail keeps starting exactly where it did before
        content.right shouldBe surface.right
        sibling.left shouldBe surface.right
    }

    @Test
    fun `the rail insets the start edge in right to left layouts`() {
        val density = renderRail(LayoutDirection.Rtl)
        val sideInset = with(density) { SIDE_INSET_PX.toDp() }

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val surface = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.CONTENT_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val sibling = composeTestRule.onNodeWithTag(SIBLING_TAG).getUnclippedBoundsInRoot()

        surface.right shouldBe root.right
        surface.top shouldBe root.top
        surface.bottom shouldBe root.bottom

        (content.right < root.right) shouldBe true
        (content.top > root.top) shouldBe true
        (content.bottom < root.bottom) shouldBe true

        (content.right - content.left) shouldBe RAIL_THICKNESS
        (root.right - content.right) shouldBe sideInset

        content.left shouldBe surface.left
        sibling.right shouldBe surface.left
    }

    @Test
    fun `the bottom rail surface spans the full width while its content is inset`() {
        val density = renderRail(LayoutDirection.Ltr, RailPlacement.BOTTOM)
        val sideInset = with(density) { SIDE_INSET_PX.toDp() }
        val bottomInset = with(density) { BOTTOM_INSET_PX.toDp() }

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val surface = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.CONTENT_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val sibling = composeTestRule.onNodeWithTag(SIBLING_TAG).getUnclippedBoundsInRoot()

        // The painted surface reaches the gesture bar and the side cutout
        surface.left shouldBe root.left
        surface.right shouldBe root.right
        surface.bottom shouldBe root.bottom

        // ...while what the user touches is moved clear of them
        (content.bottom < root.bottom) shouldBe true
        (content.left > root.left) shouldBe true

        // ...without being squeezed: the inset is added to the rail, not taken out of it
        (content.bottom - content.top) shouldBe RAIL_THICKNESS
        (root.bottom - content.bottom) shouldBe bottomInset
        (content.left - root.left) shouldBe sideInset

        // ...so the pane above the rail ends exactly where the rail begins
        content.top shouldBe surface.top
        sibling.bottom shouldBe surface.top
    }

    @Test
    fun `the bottom rail insets the side the navigation bar is on in right to left layouts`() {
        val density = renderRail(LayoutDirection.Rtl, RailPlacement.BOTTOM)
        val sideInset = with(density) { SIDE_INSET_PX.toDp() }
        val bottomInset = with(density) { BOTTOM_INSET_PX.toDp() }

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val surface = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.CONTENT_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val sibling = composeTestRule.onNodeWithTag(SIBLING_TAG).getUnclippedBoundsInRoot()

        surface.left shouldBe root.left
        surface.right shouldBe root.right
        surface.bottom shouldBe root.bottom

        (content.bottom < root.bottom) shouldBe true
        (content.right < root.right) shouldBe true

        (content.bottom - content.top) shouldBe RAIL_THICKNESS
        (root.bottom - content.bottom) shouldBe bottomInset
        (root.right - content.right) shouldBe sideInset

        content.top shouldBe surface.top
        sibling.bottom shouldBe surface.top
    }

    companion object {
        private const val ROOT_TAG = "rail.root"
        private const val SIBLING_TAG = "rail.sibling"

        private val RAIL_THICKNESS = 80.dp

        /** Shorter than the rail, so the rail's own minimum is what decides its size */
        private val ENTRY_HEIGHT = 24.dp

        /** Stand-ins for a side navigation bar, a status bar and a gesture bar */
        private const val SIDE_INSET_PX = 72
        private const val TOP_INSET_PX = 48
        private const val BOTTOM_INSET_PX = 36
    }
}

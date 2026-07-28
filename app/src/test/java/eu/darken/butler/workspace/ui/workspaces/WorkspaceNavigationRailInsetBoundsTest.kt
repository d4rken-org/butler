package eu.darken.butler.workspace.ui.workspaces

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceRailContainer
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The rail is the one surface that touches the status bar, the gesture bar and the side cutout at
 * once. Its tonal background has to be painted across all of them, so the window insets belong on
 * the content column inside the surface, never on the surface itself: inset the surface and the
 * strips above and below the rail fall back to the window background.
 *
 * The insets stay inside the surface, but they must not disappear from the rail's footprint either
 * — the pane beside it still has to start after the inset plus the full 80dp rail, and the content
 * column must keep those 80dp instead of being squeezed into 80dp including the inset.
 *
 * This drives [WorkspaceRailContainer] rather than the full rail: the rail's header renders the
 * Lottie-backed mascot, which Robolectric cannot draw, while the container holds all of the
 * geometry under test.
 *
 * The environment supplies no window insets of its own, so fixed ones are dispatched to the compose
 * view; without them every bound is identical and nothing can be told apart.
 */
class WorkspaceNavigationRailInsetBoundsTest : ComposeTest() {

    private fun renderRail(layoutDirection: LayoutDirection): Density {
        var view: View? = null
        lateinit var density: Density

        composeTestRule.setContent {
            view = LocalView.current
            density = LocalDensity.current
            // No theme wrapper on purpose: Material's defaults are enough for the geometry under
            // test, and this test shares a JVM with the rest of the module's suite.
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                Row(
                    modifier = Modifier
                        .size(width = 300.dp, height = 500.dp)
                        .testTag(ROOT_TAG),
                ) {
                    WorkspaceRailContainer {
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
        (content.right - content.left) shouldBe RAIL_WIDTH
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

        (content.right - content.left) shouldBe RAIL_WIDTH
        (root.right - content.right) shouldBe sideInset

        content.left shouldBe surface.left
        sibling.right shouldBe surface.left
    }

    companion object {
        private const val ROOT_TAG = "rail.root"
        private const val SIBLING_TAG = "rail.sibling"

        private val RAIL_WIDTH = 80.dp

        /** Stand-ins for a side navigation bar, a status bar and a gesture bar */
        private const val SIDE_INSET_PX = 72
        private const val TOP_INSET_PX = 48
        private const val BOTTOM_INSET_PX = 36
    }
}

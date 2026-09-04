package eu.darken.butler.workspace.ui.workspaces

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceFeedback
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The close-undo bar is drawn outside every pane, so nothing about the layout keeps it off the
 * navigation rail. In the bottom placement it has to clear the rail's measured height - measured,
 * because the entries grow with the font scale - and it must not add the navigation bar inset on top
 * of it, which the rail has already padded for.
 *
 * The thickness is injected rather than produced by a real rail: font scale cannot drive it here
 * (Robolectric's stub font reports one fixed height whatever the scale, see `WorkspaceRailItemTest`),
 * so an oversized rail stands in for a scaled-up one.
 *
 * The environment supplies no window insets of its own, so a navigation bar is dispatched to the
 * compose view; without it the inset case cannot be told from the no-inset one.
 */
class WorkspaceRailUndoBarClearanceTest : ComposeTest() {

    private val feedback = ClosedWorkspaceFeedback(
        closeToken = 1L,
        customTitle = null,
        automaticTitle = "Downloads".toCaString(),
    )

    private fun renderHost(bottomRailVisible: Boolean, railThickness: Dp): Density {
        var view: View? = null
        lateinit var density: Density

        composeTestRule.setContent {
            view = LocalView.current
            density = LocalDensity.current
            PreviewWrapper {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ROOT_TAG),
                ) {
                    WorkspaceClosedUndoBarHost(
                        feedback = feedback,
                        startRailVisible = false,
                        bottomRailVisible = bottomRailVisible,
                        railThickness = railThickness,
                        onUndo = {},
                        onDismiss = {},
                    )
                    if (bottomRailVisible) {
                        // Stands in for the rail the bar has to stay clear of
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(railThickness)
                                .testTag(RAIL_TAG),
                        )
                    }
                }
            }
        }

        composeTestRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(0, 0, 0, NAV_INSET_PX),
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        composeTestRule.waitForIdle()

        return density
    }

    private fun barBottom() = composeTestRule.onNodeWithText(MESSAGE)
        .onParent()
        .getUnclippedBoundsInRoot()
        .bottom

    @Test
    fun `a rail taller than its nominal thickness still gets cleared`() {
        val density = renderHost(bottomRailVisible = true, railThickness = SCALED_RAIL)
        val navInset = with(density) { NAV_INSET_PX.toDp() }

        val railTop = composeTestRule.onNodeWithTag(RAIL_TAG).getUnclippedBoundsInRoot().top

        (barBottom() <= railTop) shouldBe true
        // The rail's height already contains the navigation bar inset, so the gap above it stays
        // smaller than that inset instead of counting it a second time.
        ((railTop - barBottom()) < navInset) shouldBe true
    }

    @Test
    fun `a rail at its nominal thickness gets cleared`() {
        val density = renderHost(bottomRailVisible = true, railThickness = NOMINAL_RAIL)
        val navInset = with(density) { NAV_INSET_PX.toDp() }

        val railTop = composeTestRule.onNodeWithTag(RAIL_TAG).getUnclippedBoundsInRoot().top

        (barBottom() <= railTop) shouldBe true
        ((railTop - barBottom()) < navInset) shouldBe true
    }

    @Test
    fun `without a bottom rail the bar keeps its navigation bar inset`() {
        val density = renderHost(bottomRailVisible = false, railThickness = NOMINAL_RAIL)
        val navInset = with(density) { NAV_INSET_PX.toDp() }

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val fromBottom = root.bottom - barBottom()

        (fromBottom >= navInset) shouldBe true
        // ...and nothing beyond it: no rail padding is taken when there is no rail
        (fromBottom < NOMINAL_RAIL) shouldBe true
    }

    companion object {
        private const val ROOT_TAG = "undo.root"
        private const val RAIL_TAG = "undo.rail"

        private const val MESSAGE = "Closed \"Downloads\""

        private val NOMINAL_RAIL = 80.dp

        /** What a large font scale makes of the rail on a device */
        private val SCALED_RAIL = 140.dp

        /** Stand-in for a gesture bar */
        private const val NAV_INSET_PX = 36
    }
}

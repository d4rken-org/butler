package eu.darken.butler.workspace.ui.insets

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The Editor is the only production stack that asks to rise above the keyboard, and that used to
 * require the pane to touch the bottom window edge. Chrome the app draws below a pane (the bottom
 * navigation rail) clears that edge without moving the pane away from the keyboard, so the opt-in
 * now follows [LocalPaneBottomChrome] as well - reduced by the chrome, which the stack already sits
 * above.
 *
 * The environment supplies no window insets of its own, so a keyboard is dispatched to the compose
 * view; without it there is no extra to measure.
 */
class EditorImeInsetTest : ComposeTest() {

    private val floatingPane = PaneEdges(
        touchesTop = true,
        touchesBottom = false,
        touchesStart = true,
        touchesEnd = true,
    )

    private lateinit var density: Density

    private fun renderStack(bottomChrome: Dp): FloatingBarStackState {
        var view: View? = null
        lateinit var state: FloatingBarStackState

        composeTestRule.setContent {
            view = LocalView.current
            density = LocalDensity.current
            CompositionLocalProvider(LocalPaneBottomChrome provides bottomChrome) {
                state = rememberPaneFloatingBarStackState(
                    position = BarPosition.BOTTOM,
                    design = WorkspaceDesign(paneEdges = floatingPane),
                    edgePadding = EDGE_PADDING,
                    includeImeInset = true,
                )
            }
        }

        composeTestRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.systemBars(),
                    Insets.of(0, 0, 0, NAV_INSET_PX),
                )
                .setInsets(
                    WindowInsetsCompat.Type.ime(),
                    Insets.of(0, 0, 0, IME_INSET_PX),
                )
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        composeTestRule.waitForIdle()

        return state
    }

    @Test
    fun `a pane with chrome below it still rises above the keyboard`() {
        val state = renderStack(bottomChrome = CHROME)

        val chromePx = with(density) { CHROME.toPx() }
        val edgePaddingPx = with(density) { EDGE_PADDING.toPx() }

        // The pane does not touch the window edge, so no system bar inset is in play - only the
        // keyboard, less the chrome that already stands between the two.
        state.contentPaddingPx shouldBe (IME_INSET_PX.toFloat() - chromePx + edgePaddingPx)
    }

    @Test
    fun `a pane with neither the window edge nor chrome below it gets no keyboard extra`() {
        val state = renderStack(bottomChrome = 0.dp)

        val edgePaddingPx = with(density) { EDGE_PADDING.toPx() }

        state.contentPaddingPx shouldBe edgePaddingPx
    }

    companion object {
        private val EDGE_PADDING = 8.dp

        /** Stand-in for the navigation rail in its bottom placement */
        private val CHROME = 60.dp

        private const val NAV_INSET_PX = 36
        private const val IME_INSET_PX = 300
    }
}

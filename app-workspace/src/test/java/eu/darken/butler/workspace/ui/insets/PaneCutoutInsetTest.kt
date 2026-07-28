package eu.darken.butler.workspace.ui.insets

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eu.darken.butler.common.compose.LocalAvoidDisplayCutout
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The cutout part of a pane's horizontal inset is user-controlled, the system bar part is not.
 *
 * Both inset types are dispatched with different sizes, so the two contributions can be told apart:
 * dropping cutout avoidance has to shrink the padding to the system bar inset, never to zero. The
 * environment supplies no window insets of its own, hence the explicit dispatch.
 */
class PaneCutoutInsetTest : ComposeTest() {

    private val startEdge = PaneEdges(
        touchesTop = false,
        touchesBottom = false,
        touchesStart = true,
        touchesEnd = false,
    )

    @Test
    fun `in Ltr dropping cutout avoidance shrinks the pane inset to the system bar`() {
        renderCases(LayoutDirection.Ltr)

        val avoided = startInset(AVOIDED_CONTAINER_TAG, AVOIDED_CONTENT_TAG, LayoutDirection.Ltr)
        val reclaimed = startInset(RECLAIMED_CONTAINER_TAG, RECLAIMED_CONTENT_TAG, LayoutDirection.Ltr)
        val default = startInset(DEFAULT_CONTAINER_TAG, DEFAULT_CONTENT_TAG, LayoutDirection.Ltr)

        withClue("avoided=$avoided reclaimed=$reclaimed") {
            (avoided > reclaimed) shouldBe true
            (reclaimed > 0.dp) shouldBe true
        }
        withClue("default=$default reclaimed=$reclaimed") {
            default shouldBe reclaimed
        }
    }

    @Test
    fun `in Rtl dropping cutout avoidance shrinks the pane inset to the system bar`() {
        renderCases(LayoutDirection.Rtl)

        val avoided = startInset(AVOIDED_CONTAINER_TAG, AVOIDED_CONTENT_TAG, LayoutDirection.Rtl)
        val reclaimed = startInset(RECLAIMED_CONTAINER_TAG, RECLAIMED_CONTENT_TAG, LayoutDirection.Rtl)
        val default = startInset(DEFAULT_CONTAINER_TAG, DEFAULT_CONTENT_TAG, LayoutDirection.Rtl)

        withClue("avoided=$avoided reclaimed=$reclaimed") {
            (avoided > reclaimed) shouldBe true
            (reclaimed > 0.dp) shouldBe true
        }
        withClue("default=$default reclaimed=$reclaimed") {
            default shouldBe reclaimed
        }
    }

    /** Distance between the container's start edge and the padded content's start edge. */
    private fun startInset(containerTag: String, contentTag: String, layoutDirection: LayoutDirection): Dp {
        val container = composeTestRule.onNodeWithTag(containerTag).getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag(contentTag).getUnclippedBoundsInRoot()
        return when (layoutDirection) {
            LayoutDirection.Ltr -> content.left - container.left
            LayoutDirection.Rtl -> container.right - content.right
        }
    }

    private fun renderCases(layoutDirection: LayoutDirection) {
        var view: View? = null

        composeTestRule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                Column {
                    CompositionLocalProvider(LocalAvoidDisplayCutout provides true) {
                        InsetCase(containerTag = AVOIDED_CONTAINER_TAG, contentTag = AVOIDED_CONTENT_TAG)
                    }
                    CompositionLocalProvider(LocalAvoidDisplayCutout provides false) {
                        InsetCase(containerTag = RECLAIMED_CONTAINER_TAG, contentTag = RECLAIMED_CONTENT_TAG)
                    }
                    // Unprovided: falls back to the CompositionLocal's own default
                    InsetCase(containerTag = DEFAULT_CONTAINER_TAG, contentTag = DEFAULT_CONTENT_TAG)
                }
            }
        }

        // Insets land on the side the pane's start edge resolves to, so start/end resolution is exercised
        val startIsLeft = layoutDirection == LayoutDirection.Ltr
        composeTestRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), sideInsets(SYSTEM_BAR_INSET_PX, startIsLeft))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), sideInsets(CUTOUT_INSET_PX, startIsLeft))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        composeTestRule.waitForIdle()
    }

    @Composable
    private fun InsetCase(containerTag: String, contentTag: String) {
        Box(
            modifier = Modifier
                .size(width = 300.dp, height = 100.dp)
                .testTag(containerTag),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .paneHorizontalInsetPadding(startEdge),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(contentTag),
                )
            }
        }
    }

    private fun sideInsets(px: Int, onLeft: Boolean): Insets = when {
        onLeft -> Insets.of(px, 0, 0, 0)
        else -> Insets.of(0, 0, px, 0)
    }

    companion object {
        private const val AVOIDED_CONTAINER_TAG = "avoided.container"
        private const val AVOIDED_CONTENT_TAG = "avoided.content"
        private const val RECLAIMED_CONTAINER_TAG = "reclaimed.container"
        private const val RECLAIMED_CONTENT_TAG = "reclaimed.content"
        private const val DEFAULT_CONTAINER_TAG = "default.container"
        private const val DEFAULT_CONTENT_TAG = "default.content"

        /** Stands in for a side navigation bar; smaller than the cutout so the two can be told apart. */
        private const val SYSTEM_BAR_INSET_PX = 24
        private const val CUTOUT_INSET_PX = 72
    }
}

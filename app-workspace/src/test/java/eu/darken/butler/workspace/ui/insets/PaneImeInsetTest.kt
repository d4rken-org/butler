package eu.darken.butler.workspace.ui.insets

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheetDefaults
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication

/**
 * The keyboard's inset is a window value, but a pane only meets as much of it as reaches past the
 * pane's own bottom edge. Every case here drives that distance by hosting the component a known
 * distance above the root bottom, which is what the mask measures - not the pane edge model, which
 * says nothing about how far above the window bottom a pane sits.
 *
 * The environment supplies no window insets of its own, so a keyboard is dispatched to the compose
 * view; without it there is nothing to mask.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h800dp")
class PaneImeInsetTest : ComposeTest() {

    private lateinit var view: View
    private lateinit var density: Density

    @Test
    fun `a dialog out of the keyboard's reach reserves nothing`() {
        setDialog(gap = FAR_GAP)

        dispatchIme(IME_INSET)

        dialogImePadding() shouldBe 0.dp
    }

    @Test
    fun `a dialog on the window bottom reserves the whole keyboard`() {
        setDialog(gap = 0.dp)

        dispatchIme(IME_INSET)

        dialogImePadding() shouldBe IME_INSET
    }

    @Test
    fun `a dialog with chrome below it reserves the keyboard less that chrome`() {
        setDialog(gap = NEAR_GAP)

        dispatchIme(IME_INSET)

        dialogImePadding() shouldBe IME_INSET - NEAR_GAP
    }

    @Test
    fun `the reservation follows the keyboard up and back down`() {
        setDialog(gap = NEAR_GAP)

        listOf(
            0.dp to 0.dp,
            NEAR_GAP / 2 to 0.dp,
            IME_INSET to IME_INSET - NEAR_GAP,
            0.dp to 0.dp,
        ).forEach { (imeInset, expected) ->
            dispatchIme(imeInset)
            withClue("ime=$imeInset") { dialogImePadding() shouldBe expected }
        }
    }

    @Test
    fun `a sheet out of the keyboard's reach does not grow`() {
        setSheet(gap = FAR_GAP)
        val baseline = cardHeight()

        dispatchIme(IME_INSET)

        cardHeight() shouldBe baseline
    }

    @Test
    fun `a sheet on the window bottom grows by the whole keyboard`() {
        setSheet(gap = 0.dp)
        val baseline = cardHeight()

        dispatchIme(IME_INSET)

        cardHeight() shouldBe baseline + IME_INSET
    }

    @Test
    fun `a sheet with chrome below it grows by the keyboard less that chrome`() {
        setSheet(gap = NEAR_GAP)
        val baseline = cardHeight()

        dispatchIme(IME_INSET)

        cardHeight() shouldBe baseline + (IME_INSET - NEAR_GAP)
    }

    /**
     * A host-supplied bottom inset covers window space the keyboard's inset already spans, so the
     * two union instead of stacking - the sheet grows only by what the keyboard adds on top of it.
     */
    @Test
    fun `a sheet unions its bottom inset with the keyboard`() {
        setSheet(gap = 0.dp, bottomInset = BOTTOM_INSET)
        val baseline = cardHeight()

        dispatchIme(IME_INSET)

        cardHeight() shouldBe baseline + (IME_INSET - BOTTOM_INSET)
    }

    private fun setDialog(gap: Dp) {
        composeTestRule.setContent {
            view = LocalView.current
            density = LocalDensity.current
            Pane(gap = gap) {
                PaneBoundAlertDialog(
                    onDismissRequest = {},
                    includeImePadding = true,
                    text = { TallContent() },
                    confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                )
            }
        }
    }

    private fun setSheet(gap: Dp, bottomInset: Dp = 0.dp) {
        composeTestRule.setContent {
            view = LocalView.current
            density = LocalDensity.current
            Pane(gap = gap) {
                PaneScopedBottomSheet(
                    visible = true,
                    onDismiss = {},
                    bottomInset = bottomInset,
                    includeImePadding = true,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ITEM_HEIGHT),
                    )
                }
            }
        }
    }

    /**
     * A pane whose bottom edge sits [gap] above the bottom of the root, standing in for another pane
     * or a bottom navigation rail below it.
     */
    @Composable
    private fun Pane(gap: Dp, content: @Composable () -> Unit) {
        PreviewWrapper {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = gap),
            ) {
                PaneLayerHost(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(PANE_TAG),
                    paneFocused = true,
                ) {
                    content()
                }
            }
        }
    }

    /** Taller than the pane, so the surface is clamped and its bottom tracks the reservation. */
    @Composable
    private fun TallContent() {
        Column(modifier = Modifier.fillMaxWidth()) {
            repeat(ITEM_COUNT) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ITEM_HEIGHT),
                )
            }
        }
    }

    private fun dispatchIme(imeInset: Dp) {
        val imeInsetPx = with(density) { imeInset.roundToPx() }
        composeTestRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeInsetPx))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        composeTestRule.waitForIdle()
    }

    /**
     * The centering container shrinks by the reservation, so the clamped surface ends that much
     * higher, plus the container's own padding.
     */
    private fun dialogImePadding(): Dp {
        val pane = composeTestRule.onNodeWithTag(PANE_TAG).getUnclippedBoundsInRoot()
        val surface = composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()
        return pane.bottom - CONTAINER_PADDING - surface.bottom
    }

    private fun cardHeight(): Dp = composeTestRule
        .onNodeWithTag(PaneScopedBottomSheetDefaults.CARD_TEST_TAG)
        .getUnclippedBoundsInRoot()
        .height

    companion object {
        private val IME_INSET = 200.dp

        /** Keyboard reaches past this pane's bottom edge, but not by its full height */
        private val NEAR_GAP = 48.dp

        /** Deeper than the keyboard is tall, so none of it reaches the pane */
        private val FAR_GAP = 240.dp

        private val BOTTOM_INSET = 32.dp

        /** [PaneBoundAlertDialog]'s own padding around the surface */
        private val CONTAINER_PADDING = 24.dp

        private val ITEM_HEIGHT = 40.dp
        private const val ITEM_COUNT = 30

        private const val PANE_TAG = "workspace.insets.pane"
    }
}

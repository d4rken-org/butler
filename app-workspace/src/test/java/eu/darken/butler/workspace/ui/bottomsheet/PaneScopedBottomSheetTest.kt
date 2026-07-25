package eu.darken.butler.workspace.ui.bottomsheet

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class PaneScopedBottomSheetTest : ComposeTest() {

    /**
     * The sheet stays on screen for its exit transition after `visible` goes false. The content
     * behind it must stay inert for that whole time, not from the moment the flag flips.
     */
    @Test
    fun `the layer stays registered for the full exit transition`() {
        var visible by mutableStateOf(true)
        var contentActive: Boolean? = null

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        contentActive = LocalLayerActive.current
                    }
                    PaneScopedBottomSheet(visible = visible, onDismiss = {}) {
                        Text("sheet")
                    }
                }
            }
        }

        composeTestRule.mainClock.advanceTimeBy(1_000)
        contentActive shouldBe false

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.mainClock.advanceTimeBy(50)
        contentActive shouldBe false

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        contentActive shouldBe true
    }

    @Test
    fun `a hidden sheet does not register a layer`() {
        var contentActive: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        contentActive = LocalLayerActive.current
                    }
                    PaneScopedBottomSheet(visible = false, onDismiss = {}) {
                        Text("sheet")
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        contentActive shouldBe true
    }
}

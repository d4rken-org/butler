package eu.darken.butler.workspace.ui.bottomsheet

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
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

    /**
     * The sheet's own back handler has to live inside its layer and last as long as it: gated on
     * `visible` it would switch off during the exit transition, while the page handlers below stay
     * disabled — and back would fall through to the activity's exit handler.
     */
    @Test
    fun `back stays with the sheet for the full exit transition`() {
        var visible by mutableStateOf(true)
        var sheetDismissed = 0
        var pageBackFired = false
        var activityBackFired = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                BackHandler(enabled = true) { activityBackFired = true }
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        WorkspaceBackHandler { pageBackFired = true }
                    }
                    PaneScopedBottomSheet(visible = visible, onDismiss = { sheetDismissed++ }) {
                        Text("sheet")
                    }
                }
            }
        }

        composeTestRule.mainClock.advanceTimeBy(1_000)

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.mainClock.advanceTimeBy(50)
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle {
            activityBackFired shouldBe false
            pageBackFired shouldBe false
            sheetDismissed shouldBe 1
        }

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        // Once the sheet is gone the page owns back again
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.runOnIdle {
            pageBackFired shouldBe true
            activityBackFired shouldBe false
        }
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

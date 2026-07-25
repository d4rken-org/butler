package eu.darken.butler.workspace.ui.modal

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class WorkspaceBackHandlerTest : ComposeTest() {

    @Test
    fun `fires while its layer is the active one`() {
        var fired = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        WorkspaceBackHandler { fired = true }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle { fired shouldBe true }
    }

    @Test
    fun `does not fire while another layer is on top`() {
        var pageBackFired = false
        var overlayBackFired = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        WorkspaceBackHandler { pageBackFired = true }
                    }
                    PaneLayer(rank = PaneLayerRank.OVERLAY) {
                        WorkspaceBackHandler { overlayBackFired = true }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle {
            overlayBackFired shouldBe true
            pageBackFired shouldBe false
        }
    }

    @Test
    fun `does not fire while the pane is not focused`() {
        var fired = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        WorkspaceBackHandler { fired = true }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle { fired shouldBe false }
    }

    /**
     * BackHandler dispatches in LIFO registration order, which conditional composition perturbs.
     * Gating on the active layer has to make that irrelevant: a page handler that only enters
     * composition after the overlay is already up must still not win.
     */
    @Test
    fun `a page handler registered after the overlay still does not fire`() {
        var pageBackFired = false
        var overlayBackFired = false
        var showPageHandler by mutableStateOf(false)
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        if (showPageHandler) {
                            WorkspaceBackHandler { pageBackFired = true }
                        }
                    }
                    PaneLayer(rank = PaneLayerRank.OVERLAY) {
                        WorkspaceBackHandler { overlayBackFired = true }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { showPageHandler = true }
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle {
            overlayBackFired shouldBe true
            pageBackFired shouldBe false
        }
    }

    @Test
    fun `an explicitly disabled handler never fires`() {
        var fired = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        WorkspaceBackHandler(enabled = false) { fired = true }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle { fired shouldBe false }
    }
}

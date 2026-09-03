package eu.darken.butler.history.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/** The history page's sheets and dialogs render from the overlay slot, not from the page. */
class HistoryWorkspaceOverlaysTest : ComposeTest() {

    @Test
    fun `nothing renders while no overlay is requested`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(),
                    )
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun `the add-filter reset is disabled while the filter is empty`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(addFilterOpen = true),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Reset").assertIsNotEnabled()
    }

    @Test
    fun `the add-filter reset fires once the filter has values`() {
        var reset = false
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(kinds = setOf(Operation.Metadata.Kind.DELETE)),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(addFilterOpen = true),
                        onResetFilter = { reset = true },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Reset").assertIsEnabled().performClick()
        reset shouldBe true
    }

    @Test
    fun `the path scope dialog renders from the overlay slot`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(pathScopeOpen = true),
                    )
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun `the upgrade prompt renders with both of its actions`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(proPromptOpen = true),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(PRO_PROMPT_MESSAGE).assertIsDisplayed()
        composeTestRule.onNode(hasClickAction() and hasText(UPGRADE_ACTION)).assertIsDisplayed()
        composeTestRule.onNode(hasClickAction() and hasText(CANCEL_ACTION)).assertIsDisplayed()
    }

    @Test
    fun `the upgrade prompt's actions are wired to their own callbacks`() {
        var upgrades = 0
        var dismissals = 0

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(proPromptOpen = true),
                        onDismissProPrompt = { dismissals++ },
                        onProPromptUpgrade = { upgrades++ },
                    )
                }
            }
        }

        composeTestRule.onNode(hasClickAction() and hasText(UPGRADE_ACTION)).performClick()
        composeTestRule.runOnIdle {
            upgrades shouldBe 1
            dismissals shouldBe 0
        }

        composeTestRule.onNode(hasClickAction() and hasText(CANCEL_ACTION)).performClick()
        composeTestRule.runOnIdle {
            upgrades shouldBe 1
            dismissals shouldBe 1
        }
    }

    /**
     * The gate can resolve while another overlay is up. Bounds cannot tell the two dialogs apart -
     * both scrims span the pane - so back ownership is the assertion: only the top layer takes it.
     */
    @Test
    fun `the upgrade prompt sits above the path scope dialog`() {
        var promptDismissals = 0
        var pathScopeDismissals = 0
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(
                            pathScopeOpen = true,
                            proPromptOpen = true,
                        ),
                        onDismissPathScope = { pathScopeDismissals++ },
                        onDismissProPrompt = { promptDismissals++ },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(PRO_PROMPT_MESSAGE).assertIsDisplayed()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.runOnIdle {
            promptDismissals shouldBe 1
            pathScopeDismissals shouldBe 0
        }
    }

    companion object {
        private const val PRO_PROMPT_MESSAGE =
            "Sharing and deleting history entries is part of the upgrade. " +
                "Clearing the whole history stays free in the history settings."
        private const val UPGRADE_ACTION = "Upgrade"
        private const val CANCEL_ACTION = "Cancel"
    }
}

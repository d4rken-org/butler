package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogState
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

/** The apps page's dialogs render from the overlay slot, not from the page. */
class AppsWorkspaceOverlaysTest : ComposeTest() {

    @Test
    fun `nothing renders while no dialog is requested`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    AppsWorkspaceOverlays(
                        stateSource = flowOf(
                            AppsWorkspaceViewModel.State.Ready(dialogState = AppsDialogState.None)
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Uninstall apps?").assertDoesNotExist()
    }

    @Test
    fun `the uninstall confirmation renders from the overlay slot`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    AppsWorkspaceOverlays(
                        stateSource = flowOf(
                            AppsWorkspaceViewModel.State.Ready(
                                dialogState = AppsDialogState.ConfirmUninstall(
                                    apps = listOf(AppsMockDataProvider.createMockAppItem()),
                                ),
                            )
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Uninstall apps?").assertIsDisplayed()
    }
}

package eu.darken.butler.apps.ui.details

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.core.details.components.ComponentsData
import eu.darken.butler.apps.core.details.components.ComponentsUiState
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalPaneLayerRank
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Consumer-level modality: the component sheet lives in the overlay slot while the page's back
 * handler lives in the content layer, so Back must reach the sheet first — including for the whole
 * exit transition, before the page owns it again.
 */
class AppDetailsComponentsModalityTest : ComposeTest() {

    private val activity = ComponentEntry(
        kind = ComponentKind.ACTIVITY,
        packageName = "com.android.chrome",
        className = "com.android.chrome.MainActivity",
        isExported = true,
        enabledState = ComponentEnabledState.ENABLED,
    )

    @Test
    fun `back dismisses the sheet before it navigates the route`() {
        val selected = MutableStateFlow<ComponentEntry?>(activity)
        val actions = mutableListOf<AppDetailsPageAction>()
        var dismissed = 0
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(
                        modifier = Modifier.fillMaxSize(),
                        rank = PaneLayerRank.CONTENT,
                        modal = false,
                    ) {
                        AppDetailsWorkspacePage(
                            design = WorkspaceDesign(),
                            state = AppDetailsWorkspace.State(
                                app = AppsMockDataProvider.Presets.chrome,
                                selectedTab = DetailTab.COMPONENTS,
                            ),
                            componentsState = ComponentsUiState.Ready(
                                ComponentsData(activities = listOf(activity)),
                            ),
                            workspaceId = Workspace.Id(),
                            onPageAction = { actions += it },
                        )
                    }
                    CompositionLocalProvider(LocalPaneLayerRank provides PaneLayerRank.OVERLAY) {
                        AppDetailsWorkspaceOverlays(
                            selectedSource = selected,
                            onDismiss = {
                                dismissed++
                                selected.value = null
                            },
                        )
                    }
                }
            }
        }

        composeTestRule.mainClock.advanceTimeBy(1_000)

        // The sheet is up: back dismisses it and the route stays put.
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.runOnIdle {
            dismissed shouldBe 1
            actions shouldBe emptyList()
        }

        // Still running its exit transition: the page's handler must stay inactive.
        composeTestRule.mainClock.advanceTimeBy(50)
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.runOnIdle {
            actions shouldBe emptyList()
            dismissed shouldBe 2
        }

        // Once the sheet is gone the page owns back again.
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.runOnIdle {
            actions shouldBe listOf(AppDetailsPageAction.NavigateToTab(DetailTab.OVERVIEW))
        }
    }
}

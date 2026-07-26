package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

/** The component sheet renders from the app details overlay slot, not from the page. */
class AppDetailsWorkspaceOverlaysTest : ComposeTest() {

    private val activity = ComponentEntry(
        kind = ComponentKind.ACTIVITY,
        packageName = "com.example.app",
        className = "com.example.app.MainActivity",
        isExported = true,
        enabledState = ComponentEnabledState.ENABLED,
    )

    private fun setOverlays(selectedSource: Flow<ComponentEntry?>) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    AppDetailsWorkspaceOverlays(selectedSource = selectedSource)
                }
            }
        }
    }

    @Test
    fun `nothing renders while nothing is selected`() {
        setOverlays(flowOf(null))

        composeTestRule.onNodeWithText("MainActivity").assertDoesNotExist()
    }

    @Test
    fun `the sheet renders from the overlay slot for a selection`() {
        setOverlays(flowOf(activity))

        composeTestRule.onNodeWithText("MainActivity").assertExists()
        composeTestRule.onNodeWithText("Copy component name").assertExists()
    }

    /**
     * A remount over an existing selection has to show the sheet on the very first frame: a null
     * frame would briefly unmount the sheet's pane layer and re-arm the page's back handler. With
     * the clock held, a flow emission cannot have arrived yet — only the StateFlow's current value.
     */
    @Test
    fun `an existing selection is shown without a null frame`() {
        composeTestRule.mainClock.autoAdvance = false

        setOverlays(MutableStateFlow(activity))
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithText("MainActivity").assertExists()
    }
}

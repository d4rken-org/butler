package eu.darken.butler.workspace.ui.workspaces.adaptive

import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.parcelize.Parcelize

private val TAG = logTag("Workspace", "Container", "Adaptive")

/**
 * Creates a callback function for divider position changes.
 * This helper ensures that divider positions are always updated with the current state,
 * avoiding stale closure issues.
 */
private fun createDividerCallback(
    getCurrentDividerPositions: () -> DividerPositions,
    onDividerPositionsChange: (DividerPositions) -> Unit,
    updatePosition: DividerPositions.(Float) -> DividerPositions,
): (Float) -> Unit = { newPos ->
    val current = getCurrentDividerPositions()
    onDividerPositionsChange(current.updatePosition(newPos))
}


/**
 * Holds the positions of dividers for different workspace layouts.
 * Each position is a float between 0.2f and 0.8f representing the percentage
 * of space allocated to the first pane in each split.
 */
@Parcelize
data class DividerPositions(
    val dualVertical: Float = 0.5f,
    val dualHorizontal: Float = 0.5f,
    val tripleMain: Float = 0.5f,
    val tripleSecondary: Float = 0.5f,
) : Parcelable {
    fun withDualVertical(value: Float) = copy(dualVertical = value)
    fun withDualHorizontal(value: Float) = copy(dualHorizontal = value)
    fun withTripleMain(value: Float) = copy(tripleMain = value)
    fun withTripleSecondary(value: Float) = copy(tripleSecondary = value)
}

/**
 * An adaptive container that displays workspaces in different layouts based on the design.
 * Supports single, dual (vertical/horizontal), and triple pane layouts with draggable dividers.
 *
 * @param design The workspace design configuration determining the layout
 * @param selected List of selected workspaces to display
 * @param focusedTabId The ID of the currently focused workspace
 * @param dividerPositions Current positions of the dividers
 * @param onDividerPositionsChange Callback when divider positions change
 * @param getCurrentDividerPositions Function to get the latest divider positions (used to avoid stale closures)
 * @param onTabFocus Callback when a workspace tab receives focus
 * @param showPaneNumbers Whether to show pane numbers for workspace assignment
 * @param paneContent Content to display for each workspace
 */
@Composable
fun AdaptiveWorkspaceContainer(
    modifier: Modifier = Modifier,
    design: WorkspaceDesign = WorkspaceDesign(),
    selected: List<Workspace.Info>,
    focusedTabId: Workspace.Id?,
    dividerPositions: DividerPositions,
    onDividerPositionsChange: (DividerPositions) -> Unit,
    getCurrentDividerPositions: () -> DividerPositions = { dividerPositions },
    onTabFocus: (Workspace.Id) -> Unit,
    showPaneNumbers: Boolean = false,
    paneContent: @Composable (Workspace.Info?) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { coordinates ->
                containerSize = coordinates.size
            }
    ) {
        val dividerCallbackFactory: (DividerPositions.(Float) -> DividerPositions) -> (Float) -> Unit = { updateFn ->
            createDividerCallback(getCurrentDividerPositions, onDividerPositionsChange, updateFn)
        }
        
        when (design.layout) {
            WorkspaceDesign.Layout.SINGLE -> {
                SinglePaneLayout(
                    selected = selected,
                    focusedTabId = focusedTabId,
                    showPaneNumbers = showPaneNumbers,
                    onTabFocus = onTabFocus,
                    paneContent = paneContent,
                )
            }

            WorkspaceDesign.Layout.DUAL_VERTICAL -> {
                DualVerticalLayout(
                    selected = selected,
                    focusedTabId = focusedTabId,
                    dividerPositions = dividerPositions,
                    containerSize = containerSize,
                    showPaneNumbers = showPaneNumbers,
                    onTabFocus = onTabFocus,
                    createDividerCallback = dividerCallbackFactory,
                    paneContent = paneContent,
                )
            }

            WorkspaceDesign.Layout.DUAL_HORIZONTAL -> {
                DualHorizontalLayout(
                    selected = selected,
                    focusedTabId = focusedTabId,
                    dividerPositions = dividerPositions,
                    containerSize = containerSize,
                    showPaneNumbers = showPaneNumbers,
                    onTabFocus = onTabFocus,
                    createDividerCallback = dividerCallbackFactory,
                    paneContent = paneContent,
                )
            }

            WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT -> {
                TripleMainLeftLayout(
                    selected = selected,
                    focusedTabId = focusedTabId,
                    dividerPositions = dividerPositions,
                    containerSize = containerSize,
                    showPaneNumbers = showPaneNumbers,
                    onTabFocus = onTabFocus,
                    createDividerCallback = dividerCallbackFactory,
                    paneContent = paneContent,
                )
            }
        }
    }
}

@Preview2
@Composable
private fun AdaptiveWorkspaceContainerPreview() {
    PreviewWrapper {
        val tabs = listOf(
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Explorer".toCaString(),
            ),
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search".toCaString(),
            ),
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EDITOR,
                title = "Editor".toCaString(),
            ),
        )
        var dividerPositions by remember { mutableStateOf(DividerPositions()) }
        AdaptiveWorkspaceContainer(
            selected = tabs.take(2),
            design = WorkspaceDesign(
                layout = WorkspaceDesign.Layout.DUAL_VERTICAL,
            ),
            focusedTabId = tabs[0].id,
            dividerPositions = dividerPositions,
            onDividerPositionsChange = { dividerPositions = it },
            getCurrentDividerPositions = { dividerPositions },
            onTabFocus = {},
            showPaneNumbers = true,
            paneContent = { tab ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tab!!.title.get(LocalContext.current))
                }
            }
        )
    }
}
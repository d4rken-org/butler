package eu.darken.butler.workspace.ui.workspaces.adaptive.layouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.adaptive.ResizingDivider
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspacePaneWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun DualHorizontalLayout(
    selected: Map<Int, Workspace.Info>,
    focusedTabId: Workspace.Id?,
    dividerPositions: DividerPositions,
    containerSize: IntSize,
    showPaneNumbers: Boolean,
    showPaneOverlay: Boolean,
    onTabFocus: (Workspace.Id) -> Unit,
    onDividerPositionsChange: (DividerPositions) -> Unit,
    paneContent: @Composable (Workspace.Info?, Int) -> Unit,
) {
    val showFocusBorder = selected.size > 1
    
    val onDividerPositionChange = { newPos: Float ->
        val updated = dividerPositions.withDualHorizontal(newPos)
        onDividerPositionsChange(updated)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val ws1 = selected[0]
        WorkspacePaneWrapper(
            modifier = Modifier
                .weight(dividerPositions.dualHorizontal)
                .fillMaxHeight(),
            isFocused = focusedTabId == ws1?.id,
            showFocusBorder = showFocusBorder,
            onFocus = { ws1?.let { onTabFocus(it.id) } },
            paneNumber = if (showPaneNumbers) 1 else null,
            showOverlay = showPaneOverlay,
        ) {
            paneContent(ws1, 1)
        }

        ResizingDivider(
            modifier = Modifier.fillMaxWidth(),
            isVertical = false,
            position = dividerPositions.dualHorizontal,
            containerSize = containerSize,
            onPositionChange = onDividerPositionChange,
        )

        val ws2 = selected[1]
        WorkspacePaneWrapper(
            modifier = Modifier
                .weight(1f - dividerPositions.dualHorizontal)
                .fillMaxHeight(),
            isFocused = focusedTabId == ws2?.id,
            showFocusBorder = showFocusBorder,
            onFocus = { ws2?.let { onTabFocus(it.id) } },
            paneNumber = if (showPaneNumbers) 2 else null,
            showOverlay = showPaneOverlay,
        ) {
            paneContent(ws2, 2)
        }
    }
}

@Preview2
@Composable
private fun DualHorizontalLayoutPreview() {
    PreviewWrapper {
        val workspace1 = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EDITOR,
            title = "Editor".toCaString(),
        )
        val workspace2 = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.TEMPLATES,
            title = "Templates".toCaString(),
        )
        
        DualHorizontalLayout(
            selected = mapOf(0 to workspace1, 1 to workspace2),
            focusedTabId = workspace2.id,
            dividerPositions = DividerPositions(),
            containerSize = IntSize(800, 600),
            showPaneNumbers = true,
            showPaneOverlay = false,
            onTabFocus = {},
            onDividerPositionsChange = { },
        ) { ws, paneIdx ->
            // Preview content placeholder
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${ws?.title?.get(LocalContext.current) ?: "Empty"} - Pane $paneIdx",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
    }
}
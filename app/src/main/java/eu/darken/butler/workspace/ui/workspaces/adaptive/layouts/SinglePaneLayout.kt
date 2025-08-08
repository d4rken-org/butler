package eu.darken.butler.workspace.ui.workspaces.adaptive.layouts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspacePaneWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment

@Composable
internal fun SinglePaneLayout(
    selected: Map<Int, Workspace.Info>,
    focusedTabId: Workspace.Id?,
    showPaneNumbers: Boolean,
    showPaneOverlay: Boolean,
    onTabFocus: (Workspace.Id) -> Unit,
    paneContent: @Composable (Workspace.Info?, Int) -> Unit,
) {
    val ws1 = selected[0]
    WorkspacePaneWrapper(
        modifier = Modifier.fillMaxSize(),
        isFocused = focusedTabId == ws1?.id,
        showFocusBorder = false, // Single pane doesn't need focus border
        onFocus = { ws1?.let { onTabFocus(it.id) } },
        paneNumber = if (showPaneNumbers) 1 else null,
        showOverlay = showPaneOverlay,
    ) {
        paneContent(ws1, 1)
    }
}

@Preview2
@Composable
private fun SinglePaneLayoutPreview() {
    PreviewWrapper {
        val workspace = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            title = "Explorer".toCaString(),
        )
        
        SinglePaneLayout(
            selected = mapOf(0 to workspace),
            focusedTabId = workspace.id,
            showPaneNumbers = true,
            showPaneOverlay = false,
            onTabFocus = {},
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
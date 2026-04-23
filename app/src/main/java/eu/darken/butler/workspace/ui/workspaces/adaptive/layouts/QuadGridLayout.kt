package eu.darken.butler.workspace.ui.workspaces.adaptive.layouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.adaptive.ResizingDivider
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspacePaneWrapper
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo

@Composable
internal fun QuadGridLayout(
    selected: Map<Int, WorkspacePaneInfo>,
    focusedTabId: Workspace.Id?,
    dividerPositions: DividerPositions,
    containerSize: IntSize,
    showPaneNumbers: Boolean,
    showPaneOverlay: Boolean,
    onTabFocus: (Workspace.Id) -> Unit,
    onDividerPositionsChange: (DividerPositions) -> Unit,
    paneContent: @Composable (WorkspacePaneInfo?, Int) -> Unit,
) {
    val showFocusBorder = selected.size > 1

    val onTripleMainChange = { newPos: Float ->
        val updated = dividerPositions.withTripleMain(newPos)
        onDividerPositionsChange(updated)
    }

    val onTripleSecondaryChange = { newPos: Float ->
        val updated = dividerPositions.withTripleSecondary(newPos)
        onDividerPositionsChange(updated)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Calculate column size based on container size and divider position
        val columnWidth = (containerSize.width * dividerPositions.tripleMain).toInt()
        val columnSize = IntSize(columnWidth, containerSize.height)

        // Left column (panes 1 and 2)
        Column(
            modifier = Modifier
                .weight(dividerPositions.tripleMain)
                .fillMaxHeight()
        ) {
            val ws1 = selected[0]
            WorkspacePaneWrapper(
                modifier = Modifier
                    .weight(dividerPositions.tripleSecondary)
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
                position = dividerPositions.tripleSecondary,
                containerSize = columnSize,
                onPositionChange = onTripleSecondaryChange,
            )

            val ws2 = selected[1]
            WorkspacePaneWrapper(
                modifier = Modifier
                    .weight(1f - dividerPositions.tripleSecondary)
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

        ResizingDivider(
            modifier = Modifier.fillMaxHeight(),
            isVertical = true,
            position = dividerPositions.tripleMain,
            containerSize = containerSize,
            onPositionChange = onTripleMainChange,
        )

        // Right column (panes 3 and 4)
        val rightColumnWidth = (containerSize.width * (1f - dividerPositions.tripleMain)).toInt()
        val rightColumnSize = IntSize(rightColumnWidth, containerSize.height)

        Column(
            modifier = Modifier
                .weight(1f - dividerPositions.tripleMain)
                .fillMaxHeight()
        ) {
            val ws3 = selected[2]
            WorkspacePaneWrapper(
                modifier = Modifier
                    .weight(dividerPositions.tripleSecondary)
                    .fillMaxHeight(),
                isFocused = focusedTabId == ws3?.id,
                showFocusBorder = showFocusBorder,
                onFocus = { ws3?.let { onTabFocus(it.id) } },
                paneNumber = if (showPaneNumbers) 3 else null,
                showOverlay = showPaneOverlay,
            ) {
                paneContent(ws3, 3)
            }

            ResizingDivider(
                modifier = Modifier.fillMaxWidth(),
                isVertical = false,
                position = dividerPositions.tripleSecondary,
                containerSize = rightColumnSize,
                onPositionChange = onTripleSecondaryChange,
            )

            val ws4 = selected[3]
            WorkspacePaneWrapper(
                modifier = Modifier
                    .weight(1f - dividerPositions.tripleSecondary)
                    .fillMaxHeight(),
                isFocused = focusedTabId == ws4?.id,
                showFocusBorder = showFocusBorder,
                onFocus = { ws4?.let { onTabFocus(it.id) } },
                paneNumber = if (showPaneNumbers) 4 else null,
                showOverlay = showPaneOverlay,
            ) {
                paneContent(ws4, 4)
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun QuadGridLayoutPreview() {
    val workspace1 = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
    )
    val workspace2 = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.SEARCHER,
        title = "Search".toCaString(),
    )
    val workspace3 = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EDITOR,
        title = "Editor".toCaString(),
    )
    val workspace4 = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.TEMPLATES,
        title = "Templates".toCaString(),
    )

    QuadGridLayout(
        selected = mapOf(
            0 to workspace1.asPaneInfo(),
            1 to workspace2.asPaneInfo(),
            2 to workspace3.asPaneInfo(),
            3 to workspace4.asPaneInfo(),
        ),
        focusedTabId = workspace1.id,
        dividerPositions = DividerPositions(),
        containerSize = IntSize(1200, 800),
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
                    text = "${ws?.type} - ${ws?.id?.shortTag ?: "Empty"} - Pane $paneIdx",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
    }
}

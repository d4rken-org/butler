package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import eu.darken.butler.workspace.core.Workspace

@Composable
internal fun SinglePaneLayout(
    selected: List<Workspace.Info>,
    focusedTabId: Workspace.Id?,
    showPaneNumbers: Boolean,
    onTabFocus: (Workspace.Id) -> Unit,
    paneContent: @Composable (Workspace.Info?, Int) -> Unit,
) {
    val ws1 = selected.getOrNull(0)
    WorkspacePaneWrapper(
        modifier = Modifier.fillMaxSize(),
        isFocused = focusedTabId == ws1?.id,
        showFocusBorder = false, // Single pane doesn't need focus border
        onFocus = { ws1?.let { onTabFocus(it.id) } },
        paneNumber = if (showPaneNumbers) 1 else null,
    ) {
        paneContent(ws1, 1)
    }
}

@Composable
internal fun DualVerticalLayout(
    selected: List<Workspace.Info>,
    focusedTabId: Workspace.Id?,
    dividerPositions: DividerPositions,
    containerSize: IntSize,
    showPaneNumbers: Boolean,
    onTabFocus: (Workspace.Id) -> Unit,
    createDividerCallback: (DividerPositions.(Float) -> DividerPositions) -> (Float) -> Unit,
    paneContent: @Composable (Workspace.Info?, Int) -> Unit,
) {
    val showFocusBorder = selected.size > 1

    Row(modifier = Modifier.fillMaxSize()) {
        val ws1 = selected.getOrNull(0)
        WorkspacePaneWrapper(
            modifier = Modifier
                .weight(dividerPositions.dualVertical)
                .fillMaxHeight(),
            isFocused = focusedTabId == ws1?.id,
            showFocusBorder = showFocusBorder,
            onFocus = { ws1?.let { onTabFocus(it.id) } },
            paneNumber = if (showPaneNumbers) 1 else null,
        ) {
            paneContent(ws1, 1)
        }

        ResizingDivider(
            modifier = Modifier.fillMaxHeight(),
            isVertical = true,
            position = dividerPositions.dualVertical,
            containerSize = containerSize,
            onPositionChange = createDividerCallback(DividerPositions::withDualVertical),
        )

        val ws2 = selected.getOrNull(1)
        WorkspacePaneWrapper(
            modifier = Modifier
                .weight(1f - dividerPositions.dualVertical)
                .fillMaxHeight(),
            isFocused = focusedTabId == ws2?.id,
            showFocusBorder = showFocusBorder,
            onFocus = { ws2?.let { onTabFocus(it.id) } },
            paneNumber = if (showPaneNumbers) 2 else null,
        ) {
            paneContent(ws2, 2)
        }
    }
}

@Composable
internal fun DualHorizontalLayout(
    selected: List<Workspace.Info>,
    focusedTabId: Workspace.Id?,
    dividerPositions: DividerPositions,
    containerSize: IntSize,
    showPaneNumbers: Boolean,
    onTabFocus: (Workspace.Id) -> Unit,
    createDividerCallback: (DividerPositions.(Float) -> DividerPositions) -> (Float) -> Unit,
    paneContent: @Composable (Workspace.Info?, Int) -> Unit,
) {
    val showFocusBorder = selected.size > 1

    Column(modifier = Modifier.fillMaxSize()) {
        val ws1 = selected.getOrNull(0)
        WorkspacePaneWrapper(
            modifier = Modifier
                .weight(dividerPositions.dualHorizontal)
                .fillMaxHeight(),
            isFocused = focusedTabId == ws1?.id,
            showFocusBorder = showFocusBorder,
            onFocus = { ws1?.let { onTabFocus(it.id) } },
            paneNumber = if (showPaneNumbers) 1 else null,
        ) {
            paneContent(ws1, 1)
        }

        ResizingDivider(
            modifier = Modifier.fillMaxWidth(),
            isVertical = false,
            position = dividerPositions.dualHorizontal,
            containerSize = containerSize,
            onPositionChange = createDividerCallback(DividerPositions::withDualHorizontal),
        )

        val ws2 = selected.getOrNull(1)
        WorkspacePaneWrapper(
            modifier = Modifier
                .weight(1f - dividerPositions.dualHorizontal)
                .fillMaxHeight(),
            isFocused = focusedTabId == ws2?.id,
            showFocusBorder = showFocusBorder,
            onFocus = { ws2?.let { onTabFocus(it.id) } },
            paneNumber = if (showPaneNumbers) 2 else null,
        ) {
            paneContent(ws2, 2)
        }
    }
}

@Composable
internal fun TripleMainLeftLayout(
    selected: List<Workspace.Info>,
    focusedTabId: Workspace.Id?,
    dividerPositions: DividerPositions,
    containerSize: IntSize,
    showPaneNumbers: Boolean,
    onTabFocus: (Workspace.Id) -> Unit,
    createDividerCallback: (DividerPositions.(Float) -> DividerPositions) -> (Float) -> Unit,
    paneContent: @Composable (Workspace.Info?, Int) -> Unit,
) {
    val showFocusBorder = selected.size > 1

    Row(modifier = Modifier.fillMaxSize()) {
        val ws1 = selected.getOrNull(0)
        WorkspacePaneWrapper(
            modifier = Modifier
                .weight(dividerPositions.tripleMain)
                .fillMaxHeight(),
            isFocused = focusedTabId == ws1?.id,
            showFocusBorder = showFocusBorder,
            onFocus = { ws1?.let { onTabFocus(it.id) } },
            paneNumber = if (showPaneNumbers) 1 else null,
        ) {
            paneContent(ws1, 1)
        }

        ResizingDivider(
            modifier = Modifier.fillMaxHeight(),
            isVertical = true,
            position = dividerPositions.tripleMain,
            containerSize = containerSize,
            onPositionChange = createDividerCallback(DividerPositions::withTripleMain),
        )

        // Calculate column size based on container size and divider position
        val columnWidth = (containerSize.width * (1f - dividerPositions.tripleMain)).toInt()
        val columnSize = IntSize(columnWidth, containerSize.height)

        Column(
            modifier = Modifier
                .weight(1f - dividerPositions.tripleMain)
                .fillMaxHeight()
        ) {
            val ws2 = selected.getOrNull(1)
            WorkspacePaneWrapper(
                modifier = Modifier
                    .weight(dividerPositions.tripleSecondary)
                    .fillMaxHeight(),
                isFocused = focusedTabId == ws2?.id,
                showFocusBorder = showFocusBorder,
                onFocus = { ws2?.let { onTabFocus(it.id) } },
                paneNumber = if (showPaneNumbers) 2 else null,
            ) {
                paneContent(ws2, 2)
            }

            ResizingDivider(
                modifier = Modifier.fillMaxWidth(),
                isVertical = false,
                position = dividerPositions.tripleSecondary,
                containerSize = columnSize,
                onPositionChange = createDividerCallback(DividerPositions::withTripleSecondary),
            )

            val ws3 = selected.getOrNull(2)
            WorkspacePaneWrapper(
                modifier = Modifier
                    .weight(1f - dividerPositions.tripleSecondary)
                    .fillMaxHeight(),
                isFocused = focusedTabId == ws3?.id,
                showFocusBorder = showFocusBorder,
                onFocus = { ws3?.let { onTabFocus(it.id) } },
                paneNumber = if (showPaneNumbers) 3 else null,
            ) {
                paneContent(ws3, 3)
            }
        }
    }
}
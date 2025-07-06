package eu.darken.butler.workspace.ui.workspaces.adaptive

import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.templates.ui.WorkspaceTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.parcelize.Parcelize

private val TAG = logTag("Workspace", "Container", "Adaptive")


@Parcelize
data class DividerPositions(
    val dualVertical: Float = 0.5f,
    val dualHorizontal: Float = 0.5f,
    val tripleMain: Float = 0.5f,
    val tripleSecondary: Float = 0.5f,
) : Parcelable

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
    val showFocusBorder = selected.size > 1
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerSize = coordinates.size
            }
    ) {
        when (design.layout) {
            WorkspaceDesign.Layout.SINGLE -> {
                val ws1 = selected.getOrNull(0)
                WorkspacePaneWrapper(
                    modifier = Modifier.fillMaxSize(),
                    isFocused = focusedTabId == ws1?.id,
                    showFocusBorder = false, // Single pane doesn't need focus border
                    onFocus = { ws1?.let { onTabFocus(it.id) } },
                    paneNumber = if (showPaneNumbers) 1 else null,
                ) {
                    paneContent(ws1)
                }
            }

            WorkspaceDesign.Layout.DUAL_VERTICAL -> {
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
                        paneContent(ws1)
                    }

                    val dualVerticalCallback = { newPos: Float ->
                        val current = getCurrentDividerPositions()
                        val newPositions = DividerPositions(
                            dualVertical = newPos,
                            dualHorizontal = current.dualHorizontal,
                            tripleMain = current.tripleMain,
                            tripleSecondary = current.tripleSecondary,
                        )
                        onDividerPositionsChange(newPositions)
                    }
                    
                    ResizingDivider(
                        modifier = Modifier.fillMaxHeight(),
                        isVertical = true,
                        position = dividerPositions.dualVertical,
                        containerSize = containerSize,
                        onPositionChange = dualVerticalCallback,
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
                        paneContent(ws2)
                    }
                }
            }

            WorkspaceDesign.Layout.DUAL_HORIZONTAL -> {
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
                        paneContent(ws1)
                    }

                    val dualHorizontalCallback = { newPos: Float ->
                        val current = getCurrentDividerPositions()
                        val newPositions = DividerPositions(
                            dualVertical = current.dualVertical,
                            dualHorizontal = newPos,
                            tripleMain = current.tripleMain,
                            tripleSecondary = current.tripleSecondary,
                        )
                        onDividerPositionsChange(newPositions)
                    }
                    
                    ResizingDivider(
                        modifier = Modifier.fillMaxWidth(),
                        isVertical = false,
                        position = dividerPositions.dualHorizontal,
                        containerSize = containerSize,
                        onPositionChange = dualHorizontalCallback,
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
                        paneContent(ws2)
                    }
                }
            }

            WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT -> {
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
                        paneContent(ws1)
                    }

                    val mainDividerCallback = { newPos: Float ->
                        val current = getCurrentDividerPositions()
                        val newPositions = DividerPositions(
                            dualVertical = current.dualVertical,
                            dualHorizontal = current.dualHorizontal,
                            tripleMain = newPos,
                            tripleSecondary = current.tripleSecondary,
                        )
                        onDividerPositionsChange(newPositions)
                    }
                    
                    ResizingDivider(
                        modifier = Modifier.fillMaxHeight(),
                        isVertical = true,
                        position = dividerPositions.tripleMain,
                        containerSize = containerSize,
                        onPositionChange = mainDividerCallback,
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
                            paneContent(ws2)
                        }

                        val secondaryDividerCallback = { newPos: Float ->
                            val current = getCurrentDividerPositions()
                            val newPositions = DividerPositions(
                                dualVertical = current.dualVertical,
                                dualHorizontal = current.dualHorizontal,
                                tripleMain = current.tripleMain,
                                tripleSecondary = newPos,
                            )
                            onDividerPositionsChange(newPositions)
                        }
                        
                        ResizingDivider(
                            modifier = Modifier.fillMaxWidth(),
                            isVertical = false,
                            position = dividerPositions.tripleSecondary,
                            containerSize = columnSize,
                            onPositionChange = secondaryDividerCallback,
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
                            paneContent(ws3)
                        }
                    }
                }
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
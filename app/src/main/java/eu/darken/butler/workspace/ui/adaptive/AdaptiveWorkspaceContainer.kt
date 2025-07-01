package eu.darken.butler.workspace.ui.adaptive

import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.templates.ui.WorkspaceTab
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize

private val TAG = logTag("Workspace", "Adaptive", "Container")

enum class PaneLayout {
    SINGLE,
    DUAL_VERTICAL,
    DUAL_HORIZONTAL,
    TRIPLE_MAIN_LEFT,
}

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
    selectedTabs: List<WorkspaceTab>,
    focusedTabId: Workspace.Id?,
    paneLayout: PaneLayout,
    dividerPositions: DividerPositions,
    onDividerPositionsChange: (DividerPositions) -> Unit,
    onTabFocus: (Workspace.Id) -> Unit,
    showPaneNumbers: Boolean = false,
    tabContent: @Composable (WorkspaceTab) -> Unit,
) {
    val componentId = remember { System.currentTimeMillis() }
    log(TAG) { "AdaptiveWorkspaceContainer($componentId) recomposing - layout: $paneLayout, dividerPositions: $dividerPositions" }

    val showFocusBorder = selectedTabs.size > 1
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerSize = coordinates.size
            }
    ) {
        when (paneLayout) {
            PaneLayout.SINGLE -> {
                selectedTabs.firstOrNull()?.let { tab ->
                    WorkspacePaneWrapper(
                        modifier = Modifier.fillMaxSize(),
                        tab = tab,
                        isFocused = focusedTabId == tab.id,
                        showFocusBorder = false, // Single pane doesn't need focus border
                        onFocus = { onTabFocus(tab.id) },
                        paneNumber = if (showPaneNumbers) 1 else null,
                    ) {
                        tabContent(tab)
                    }
                }
            }

            PaneLayout.DUAL_VERTICAL -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    selectedTabs.getOrNull(0)?.let { tab ->
                        WorkspacePaneWrapper(
                            modifier = Modifier
                                .weight(dividerPositions.dualVertical)
                                .fillMaxHeight(),
                            tab = tab,
                            isFocused = focusedTabId == tab.id,
                            showFocusBorder = showFocusBorder,
                            onFocus = { onTabFocus(tab.id) },
                            paneNumber = if (showPaneNumbers) 1 else null,
                        ) {
                            tabContent(tab)
                        }
                    }

                    ResizableDivider(
                        modifier = Modifier.fillMaxHeight(),
                        isVertical = true,
                        position = dividerPositions.dualVertical,
                        containerSize = containerSize,
                        onPositionChange = { newPos ->
                            log(TAG) { "DUAL_VERTICAL divider onPositionChange - current: ${dividerPositions.dualVertical}, new: $newPos" }
                            onDividerPositionsChange(dividerPositions.copy(dualVertical = newPos))
                        },
                    )

                    selectedTabs.getOrNull(1)?.let { tab ->
                        WorkspacePaneWrapper(
                            modifier = Modifier
                                .weight(1f - dividerPositions.dualVertical)
                                .fillMaxHeight(),
                            tab = tab,
                            isFocused = focusedTabId == tab.id,
                            showFocusBorder = showFocusBorder,
                            onFocus = { onTabFocus(tab.id) },
                            paneNumber = if (showPaneNumbers) 2 else null,
                        ) {
                            tabContent(tab)
                        }
                    }
                }
            }

            PaneLayout.DUAL_HORIZONTAL -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    selectedTabs.getOrNull(0)?.let { tab ->
                        WorkspacePaneWrapper(
                            modifier = Modifier
                                .weight(dividerPositions.dualHorizontal)
                                .fillMaxWidth(),
                            tab = tab,
                            isFocused = focusedTabId == tab.id,
                            showFocusBorder = showFocusBorder,
                            onFocus = { onTabFocus(tab.id) },
                            paneNumber = if (showPaneNumbers) 1 else null,
                        ) {
                            tabContent(tab)
                        }
                    }

                    ResizableDivider(
                        modifier = Modifier.fillMaxWidth(),
                        isVertical = false,
                        position = dividerPositions.dualHorizontal,
                        containerSize = containerSize,
                        onPositionChange = { newPos ->
                            log(TAG) { "DUAL_HORIZONTAL divider onPositionChange - current: ${dividerPositions.dualHorizontal}, new: $newPos" }
                            onDividerPositionsChange(dividerPositions.copy(dualHorizontal = newPos))
                        },
                    )

                    selectedTabs.getOrNull(1)?.let { tab ->
                        WorkspacePaneWrapper(
                            modifier = Modifier
                                .weight(1f - dividerPositions.dualHorizontal)
                                .fillMaxWidth(),
                            tab = tab,
                            isFocused = focusedTabId == tab.id,
                            showFocusBorder = showFocusBorder,
                            onFocus = { onTabFocus(tab.id) },
                            paneNumber = if (showPaneNumbers) 2 else null,
                        ) {
                            tabContent(tab)
                        }
                    }
                }
            }

            PaneLayout.TRIPLE_MAIN_LEFT -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    selectedTabs.getOrNull(0)?.let { tab ->
                        WorkspacePaneWrapper(
                            modifier = Modifier
                                .weight(dividerPositions.tripleMain)
                                .fillMaxHeight(),
                            tab = tab,
                            isFocused = focusedTabId == tab.id,
                            showFocusBorder = showFocusBorder,
                            onFocus = { onTabFocus(tab.id) },
                            paneNumber = if (showPaneNumbers) 1 else null,
                        ) {
                            tabContent(tab)
                        }
                    }

                    ResizableDivider(
                        modifier = Modifier.fillMaxHeight(),
                        isVertical = true,
                        position = dividerPositions.tripleMain,
                        containerSize = containerSize,
                        onPositionChange = { newPos ->
                            log(TAG) { "TRIPLE_MAIN divider onPositionChange - current: ${dividerPositions.tripleMain}, new: $newPos" }
                            onDividerPositionsChange(dividerPositions.copy(tripleMain = newPos))
                        },
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f - dividerPositions.tripleMain)
                            .fillMaxHeight()
                    ) {
                        selectedTabs.getOrNull(1)?.let { tab ->
                            WorkspacePaneWrapper(
                                modifier = Modifier
                                    .weight(dividerPositions.tripleSecondary)
                                    .fillMaxWidth(),
                                tab = tab,
                                isFocused = focusedTabId == tab.id,
                                showFocusBorder = showFocusBorder,
                                onFocus = { onTabFocus(tab.id) },
                                paneNumber = if (showPaneNumbers) 2 else null,
                            ) {
                                tabContent(tab)
                            }
                        }

                        ResizableDivider(
                            modifier = Modifier.fillMaxWidth(),
                            isVertical = false,
                            position = dividerPositions.tripleSecondary,
                            containerSize = containerSize,
                            onPositionChange = { newPos ->
                                log(TAG) { "TRIPLE_SECONDARY divider onPositionChange - current: ${dividerPositions.tripleSecondary}, new: $newPos" }
                                onDividerPositionsChange(dividerPositions.copy(tripleSecondary = newPos))
                            },
                        )

                        selectedTabs.getOrNull(2)?.let { tab ->
                            WorkspacePaneWrapper(
                                modifier = Modifier
                                    .weight(1f - dividerPositions.tripleSecondary)
                                    .fillMaxWidth(),
                                tab = tab,
                                isFocused = focusedTabId == tab.id,
                                showFocusBorder = showFocusBorder,
                                onFocus = { onTabFocus(tab.id) },
                                paneNumber = if (showPaneNumbers) 3 else null,
                            ) {
                                tabContent(tab)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspacePaneWrapper(
    modifier: Modifier = Modifier,
    tab: WorkspaceTab,
    isFocused: Boolean,
    showFocusBorder: Boolean,
    onFocus: () -> Unit,
    paneNumber: Int?,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clickable { onFocus() }
            .then(
                if (showFocusBorder) {
                    if (isFocused) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.medium,
                        )
                    } else {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.medium,
                        )
                    }
                } else {
                    Modifier
                }
            )
            .padding(if (showFocusBorder) 2.dp else 0.dp),
    ) {
        content()

        paneNumber?.let {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(10f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                tonalElevation = 8.dp,
            ) {
                Text(
                    text = it.toString(),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ResizableDivider(
    modifier: Modifier = Modifier,
    isVertical: Boolean,
    position: Float,
    containerSize: androidx.compose.ui.unit.IntSize,
    onPositionChange: (Float) -> Unit,
) {
    val dividerId = remember { System.currentTimeMillis() }
    log(TAG) { "ResizableDivider($dividerId) recomposing - isVertical: $isVertical, position: $position, containerSize: $containerSize" }

    var isDragging by remember { mutableStateOf(false) }
    val parentSize = if (isVertical) containerSize.width.toFloat() else containerSize.height.toFloat()
    log(TAG) { "ResizableDivider($dividerId) parentSize: $parentSize" }

    // Track the current position locally to avoid stale closure issues
    var currentPosition by remember { mutableStateOf(position) }
    currentPosition = position // Update when position prop changes

    Box(
        modifier = modifier
            .then(
                if (isVertical) {
                    Modifier
                        .width(8.dp)
                        .fillMaxHeight()
                } else {
                    Modifier
                        .height(8.dp)
                        .fillMaxWidth()
                }
            )
            .pointerInput(parentSize) {
                detectDragGestures(
                    onDragStart = { offset ->
                        log(TAG) { "ResizableDivider($dividerId) onDragStart - offset: $offset, startPosition: $currentPosition" }
                        isDragging = true
                    },
                    onDragEnd = {
                        log(TAG) { "ResizableDivider($dividerId) onDragEnd - final position: $currentPosition" }
                        isDragging = false
                    },
                    onDrag = { _, dragAmount ->
                        if (parentSize > 0) {
                            val delta = if (isVertical) dragAmount.x else dragAmount.y
                            val newPosition = currentPosition + (delta / parentSize)
                            val clampedPosition = newPosition.coerceIn(0.2f, 0.8f)
                            log(TAG) { "ResizableDivider($dividerId) onDrag - delta: $delta, current: $currentPosition, new: $newPosition, clamped: $clampedPosition" }
                            currentPosition = clampedPosition
                            onPositionChange(clampedPosition)
                        } else {
                            log(TAG) { "ResizableDivider($dividerId) onDrag - skipped, parentSize is 0" }
                        }
                    }
                )
            }
            .background(
                if (isDragging) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
            .alpha(if (isDragging) 1f else 0.5f),
    )
}


@Preview2
@Composable
private fun AdaptiveWorkspaceContainerPreview() {
    PreviewWrapper {
        val tabs = listOf(
            WorkspaceTab(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Explorer".toCaString(),
            ),
            WorkspaceTab(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search".toCaString(),
            ),
            WorkspaceTab(
                id = Workspace.Id(),
                type = Workspace.Type.EDITOR,
                title = "Editor".toCaString(),
            ),
        )
        var dividerPositions by remember { mutableStateOf(DividerPositions()) }
        AdaptiveWorkspaceContainer(
            selectedTabs = tabs.take(2),
            focusedTabId = tabs[0].id,
            paneLayout = PaneLayout.DUAL_VERTICAL,
            dividerPositions = dividerPositions,
            onDividerPositionsChange = { dividerPositions = it },
            onTabFocus = {},
            showPaneNumbers = true,
            tabContent = { tab ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tab.title.get(androidx.compose.ui.platform.LocalContext.current))
                }
            }
        )
    }
}
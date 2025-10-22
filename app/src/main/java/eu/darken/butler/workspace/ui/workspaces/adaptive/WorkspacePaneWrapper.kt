package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.common.ca.toCaString

// State enum for Transition API to batch animations
private enum class PaneState {
    NORMAL,
    BORDER_UNFOCUSED,
    BORDER_FOCUSED,
    DROP_TARGET
}

@Composable
internal fun WorkspacePaneWrapper(
    modifier: Modifier = Modifier,
    isFocused: Boolean,
    showFocusBorder: Boolean,
    onFocus: () -> Unit,
    paneNumber: Int?,
    showOverlay: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dragDropState = LocalDragDropState.current
    val isDropTarget = dragDropState.isDragging && dragDropState.hoveredPaneIndex == paneNumber?.minus(1)

    // Determine current state for transition
    val currentState = remember(isDropTarget, isFocused, showFocusBorder) {
        when {
            isDropTarget -> PaneState.DROP_TARGET
            isFocused && showFocusBorder -> PaneState.BORDER_FOCUSED
            showFocusBorder -> PaneState.BORDER_UNFOCUSED
            else -> PaneState.NORMAL
        }
    }

    // Use Transition API to batch all animations together
    val transition = updateTransition(targetState = currentState, label = "paneTransition")

    val borderColor by transition.animateColor(label = "borderColor") { state ->
        when (state) {
            PaneState.DROP_TARGET -> MaterialTheme.colorScheme.primary
            PaneState.BORDER_FOCUSED -> MaterialTheme.colorScheme.primary
            PaneState.BORDER_UNFOCUSED -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            PaneState.NORMAL -> Color.Transparent
        }
    }

    val borderWidth by transition.animateDp(label = "borderWidth") { state ->
        when (state) {
            PaneState.DROP_TARGET -> 3.dp
            PaneState.BORDER_FOCUSED -> 2.dp
            PaneState.BORDER_UNFOCUSED -> 1.dp
            PaneState.NORMAL -> 0.dp
        }
    }
    Box(
        modifier = modifier
            .clickable { onFocus() }
            .then(
                if (showFocusBorder || isDropTarget) {
                    Modifier.border(
                        width = borderWidth,
                        color = borderColor,
                        shape = MaterialTheme.shapes.medium,
                    )
                } else {
                    Modifier
                }
            )
            .padding(if (showFocusBorder || isDropTarget) 2.dp else 0.dp),
    ) {
        content()

        // Show overlay for drop target
        if (isDropTarget) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .zIndex(3f)
            )
        }

        // Semi-transparent overlay covering the entire pane
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.Companion
                .matchParentSize()
                .zIndex(5f)
        ) {
            Box(
                modifier = Modifier.Companion
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            )
        }

        // Animated pane number indicator centered in the pane
        AnimatedVisibility(
            visible = showOverlay && paneNumber != null,
            enter = scaleIn(animationSpec = spring(dampingRatio = 0.7f)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.Companion
                .matchParentSize()
                .zIndex(10f)
        ) {
            paneNumber?.let { number ->
                val pulseScale by transition.animateFloat(
                    label = "pulseScale",
                    transitionSpec = { spring(dampingRatio = 0.5f) }
                ) { _ ->
                    if (showOverlay) 1f else 0.95f
                }

                // Use graphicsLayer for scale to skip layout phase
                Box(
                    modifier = Modifier.Companion
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                        },
                    contentAlignment = Alignment.Companion.Center,
                ) {
                    // Main badge - removed shadow/glow layer for performance
                    Surface(
                        modifier = Modifier.Companion
                            .size(72.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 8.dp,
                        shadowElevation = 4.dp,
                    ) {
                        Box(
                            modifier = Modifier.Companion
                                .matchParentSize()
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Companion.Center,
                        ) {
                            Text(
                                text = number.toString(),
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }

        // Original pane number display (when not showing overlay - kept for reference)
        if (!showOverlay && paneNumber != null) {
            Surface(
                modifier = Modifier.Companion
                    .align(Alignment.Companion.Center)
                    .zIndex(10f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                tonalElevation = 8.dp,
            ) {
                Text(
                    text = paneNumber.toString(),
                    modifier = Modifier.Companion.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspacePaneWrapperPreviewBorders() {
    PreviewWrapper {
        val mockDragDropState = remember { DragDropState() }
        
        CompositionLocalProvider(LocalDragDropState provides mockDragDropState) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Normal state
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Normal", style = MaterialTheme.typography.labelMedium)
                    WorkspacePaneWrapper(
                        modifier = Modifier
                            .width(250.dp)
                            .height(180.dp),
                        isFocused = false,
                        showFocusBorder = false,
                        onFocus = {},
                        paneNumber = null,
                        showOverlay = false,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Content")
                        }
                    }
                }
                
                // With border (unfocused)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("With Border", style = MaterialTheme.typography.labelMedium)
                    WorkspacePaneWrapper(
                        modifier = Modifier
                            .width(250.dp)
                            .height(180.dp),
                        isFocused = false,
                        showFocusBorder = true,
                        onFocus = {},
                        paneNumber = null,
                        showOverlay = false,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Content")
                        }
                    }
                }
                
                // Focused
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Focused", style = MaterialTheme.typography.labelMedium)
                    WorkspacePaneWrapper(
                        modifier = Modifier
                            .width(250.dp)
                            .height(180.dp),
                        isFocused = true,
                        showFocusBorder = true,
                        onFocus = {},
                        paneNumber = null,
                        showOverlay = false,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Content")
                        }
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspacePaneWrapperPreviewPaneNumbers() {
    PreviewWrapper {
        val mockDragDropState = remember { DragDropState() }
        
        CompositionLocalProvider(LocalDragDropState provides mockDragDropState) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // With pane number
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Pane Number", style = MaterialTheme.typography.labelMedium)
                    WorkspacePaneWrapper(
                        modifier = Modifier
                            .width(250.dp)
                            .height(180.dp),
                        isFocused = false,
                        showFocusBorder = false,
                        onFocus = {},
                        paneNumber = 1,
                        showOverlay = false,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Content")
                        }
                    }
                }
                
                // With overlay
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("With Overlay", style = MaterialTheme.typography.labelMedium)
                    WorkspacePaneWrapper(
                        modifier = Modifier
                            .width(250.dp)
                            .height(180.dp),
                        isFocused = false,
                        showFocusBorder = false,
                        onFocus = {},
                        paneNumber = 2,
                        showOverlay = true,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Content")
                        }
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspacePaneWrapperPreviewDropTarget() {
    PreviewWrapper {
        val dropTargetState = remember {
            DragDropState().apply {
                // Simulate a drag in progress
                startDrag(
                    Workspace.Info(
                        id = Workspace.Id(),
                        type = Workspace.Type.EXPLORER,
                        title = "Dragged Tab".toCaString(),
                    )
                )
                hoveredPaneIndex = 0  // This wrapper has paneNumber = 1
            }
        }
        
        CompositionLocalProvider(LocalDragDropState provides dropTargetState) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Drop Target Highlight", style = MaterialTheme.typography.labelMedium)
                WorkspacePaneWrapper(
                    modifier = Modifier
                        .width(400.dp)
                        .height(250.dp),
                    isFocused = false,
                    showFocusBorder = true,
                    onFocus = {},
                    paneNumber = 1,
                    showOverlay = false,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Drop workspace here")
                    }
                }
            }
        }
    }
}
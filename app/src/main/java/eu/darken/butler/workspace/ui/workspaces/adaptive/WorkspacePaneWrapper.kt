package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

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

    val borderColor by animateColorAsState(
        targetValue = when {
            isDropTarget -> MaterialTheme.colorScheme.primary
            isFocused && showFocusBorder -> MaterialTheme.colorScheme.primary
            showFocusBorder -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        label = "borderColor"
    )

    val borderWidth by animateDpAsState(
        targetValue = when {
            isDropTarget -> 3.dp
            isFocused && showFocusBorder -> 2.dp
            showFocusBorder -> 1.dp
            else -> 0.dp
        },
        label = "borderWidth"
    )
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
        
        // Show overlay when pane menu is open
        if (showOverlay) {
            // Dark overlay
            Box(
                modifier = Modifier.Companion
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .zIndex(5f)
            )
            
            // Pane number indicator
            paneNumber?.let {
                Surface(
                    modifier = Modifier.Companion
                        .align(Alignment.Companion.Center)
                        .zIndex(10f),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 8.dp,
                ) {
                    Text(
                        text = it.toString(),
                        modifier = Modifier.Companion.padding(horizontal = 32.dp, vertical = 24.dp),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        } else if (paneNumber != null) {
            // Original pane number display (when not showing overlay)
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
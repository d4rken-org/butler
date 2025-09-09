package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.debug.logging.logTag


private val TAG = logTag("Workspace", "Container", "Adaptive", "Divider")

private const val DIVIDER_WIDTH = 8  // Touch target size for dragging
private const val DIVIDER_VISIBLE_WIDTH = 2  // Actual visible divider line
private const val DIVIDER_HANDLE_SIZE = 32
private const val DIVIDER_HANDLE_WIDTH = 4
private const val MIN_POSITION = 0.2f
private const val MAX_POSITION = 0.8f

/**
 * A draggable divider component that allows users to resize panes.
 *
 * @param isVertical Whether this is a vertical divider (separates left/right) or horizontal (top/bottom)
 * @param position Current position as a fraction (0.2f to 0.8f) of the container size
 * @param containerSize The size of the container this divider is in
 * @param onPositionChange Callback when the divider is dragged to a new position
 */
@Composable
internal fun ResizingDivider(
    modifier: Modifier = Modifier,
    isVertical: Boolean,
    position: Float,
    containerSize: IntSize,
    onPositionChange: (Float) -> Unit,
) {
    var isDragging by remember { mutableStateOf(false) }
    if (isVertical) containerSize.width.toFloat() else containerSize.height.toFloat()

    // Track the current position locally to avoid stale closure issues
    var currentPosition by remember { mutableStateOf(position) }
    
    // Update currentPosition when position changes AND we're not dragging
    if (!isDragging && currentPosition != position) {
        currentPosition = position
    }
    
    // Use rememberUpdatedState to capture the current callback without recreating pointerInput
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)

    val dividerColor = if (isDragging) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    val handleColor = if (isDragging) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }

    Box(
        modifier = modifier
            .then(
                if (isVertical) {
                    Modifier
                        .width(DIVIDER_WIDTH.dp)
                        .fillMaxHeight()
                } else {
                    Modifier
                        .height(DIVIDER_WIDTH.dp)
                        .fillMaxWidth()
                }
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDrag = { _, dragAmount ->
                        val currentParentSize =
                            if (isVertical) containerSize.width.toFloat() else containerSize.height.toFloat()
                        if (currentParentSize > 0) {
                            val delta = if (isVertical) dragAmount.x else dragAmount.y
                            val newPosition = currentPosition + (delta / currentParentSize)
                            val clampedPosition = newPosition.coerceIn(MIN_POSITION, MAX_POSITION)
                            currentPosition = clampedPosition
                            currentOnPositionChange(clampedPosition)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Thin visible divider line with padding
        Box(
            modifier = Modifier
                .then(
                    if (isVertical) {
                        Modifier
                            .width(DIVIDER_VISIBLE_WIDTH.dp)
                            .fillMaxHeight()
                            .padding(vertical = 8.dp)  // Add spacing from edges
                    } else {
                        Modifier
                            .height(DIVIDER_VISIBLE_WIDTH.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)  // Add spacing from edges
                    }
                )
                .clip(RoundedCornerShape(1.dp))
                .background(dividerColor)
        )
        // Divider handle indicator
        Box(
            modifier = Modifier
                .then(
                    if (isVertical) {
                        Modifier
                            .width(DIVIDER_HANDLE_WIDTH.dp)
                            .height(DIVIDER_HANDLE_SIZE.dp)
                    } else {
                        Modifier
                            .height(DIVIDER_HANDLE_WIDTH.dp)
                            .width(DIVIDER_HANDLE_SIZE.dp)
                    }
                )
                .clip(RoundedCornerShape(2.dp))
                .background(handleColor),
        )
    }
}
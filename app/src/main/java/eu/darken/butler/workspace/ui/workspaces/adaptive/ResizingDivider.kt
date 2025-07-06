package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag


private val TAG = logTag("Workspace", "Container", "Adaptive", "Divider")

@Composable
internal fun ResizingDivider(
    modifier: Modifier = Modifier.Companion,
    isVertical: Boolean,
    position: Float,
    containerSize: IntSize,
    onPositionChange: (Float) -> Unit,
) {
    var isDragging by remember { mutableStateOf(false) }
    val parentSize = if (isVertical) containerSize.width.toFloat() else containerSize.height.toFloat()

    // Track the current position locally to avoid stale closure issues
    var currentPosition by remember { mutableStateOf(position) }
    
    // Update currentPosition when position changes AND we're not dragging
    if (!isDragging && currentPosition != position) {
        currentPosition = position
    }

    val dividerColor = if (isDragging) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = modifier
            .then(
                if (isVertical) {
                    Modifier.Companion
                        .width(12.dp)
                        .fillMaxHeight()
                } else {
                    Modifier.Companion
                        .height(12.dp)
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
                        val currentParentSize = if (isVertical) containerSize.width.toFloat() else containerSize.height.toFloat()
                        if (currentParentSize > 0) {
                            val delta = if (isVertical) dragAmount.x else dragAmount.y
                            val newPosition = currentPosition + (delta / currentParentSize)
                            val clampedPosition = newPosition.coerceIn(0.2f, 0.8f)
                            currentPosition = clampedPosition
                            onPositionChange(clampedPosition)
                        }
                    }
                )
            }
            .clip(RoundedCornerShape(6.dp))
            .background(dividerColor),
        contentAlignment = Alignment.Companion.Center,
    ) {
        // Divider handle indicator
        Box(
            modifier = Modifier.Companion
                .then(
                    if (isVertical) {
                        Modifier.Companion
                            .width(4.dp)
                            .height(32.dp)
                    } else {
                        Modifier.Companion
                            .height(4.dp)
                            .width(32.dp)
                    }
                )
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                .background(
                    if (isDragging) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    }
                ),
        )
    }
}
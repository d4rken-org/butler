package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import eu.darken.butler.workspace.core.Workspace

class DragDropState {
    var isDragging by mutableStateOf(false)
        private set

    var draggedWorkspace by mutableStateOf<Workspace.Info?>(null)
        private set

    var dragPosition by mutableStateOf(Offset.Zero)
        private set

    var hoveredPaneIndex by mutableStateOf<Int?>(null)

    fun startDrag(workspace: Workspace.Info, position: Offset = Offset.Zero) {
        isDragging = true
        draggedWorkspace = workspace
        dragPosition = position
    }

    fun updateDragPosition(position: Offset) {
        dragPosition = position
    }

    fun endDrag() {
        isDragging = false
        draggedWorkspace = null
        dragPosition = Offset.Zero
        hoveredPaneIndex = null
    }
}

val LocalDragDropState = compositionLocalOf { DragDropState() }
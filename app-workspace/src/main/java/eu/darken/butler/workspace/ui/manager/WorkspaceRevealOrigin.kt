package eu.darken.butler.workspace.ui.manager

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset

/**
 * Where the tab manager should grow from, in root coordinates.
 *
 * The slot is written at the moment a button asks to open the manager, never continuously from
 * layout, and it is never cleared. Many buttons exist at once, so a slot fed from layout would hold
 * whichever of them laid out last instead of the one the user pressed.
 */
@Stable
class WorkspaceRevealOrigin {
    var offset: Offset? by mutableStateOf(null)
}

val LocalWorkspaceRevealOrigin = staticCompositionLocalOf<WorkspaceRevealOrigin?> { null }

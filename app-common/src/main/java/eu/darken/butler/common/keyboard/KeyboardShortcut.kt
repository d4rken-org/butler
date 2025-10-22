package eu.darken.butler.common.keyboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType

/**
 * Represents a keyboard shortcut with a key and optional modifiers.
 */
data class KeyboardShortcut(
    val key: Key,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    /**
     * Checks if this shortcut matches the given keyboard event.
     * Only matches on KeyDown events to avoid duplicate triggers.
     */
    fun matches(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false

        return event.key == key &&
            event.isCtrlPressed == ctrl &&
            event.isAltPressed == alt &&
            event.isShiftPressed == shift
    }

    companion object {
        // Common shortcuts
        val Copy = KeyboardShortcut(key = Key.C, ctrl = true)
        val Cut = KeyboardShortcut(key = Key.X, ctrl = true)
        val Paste = KeyboardShortcut(key = Key.V, ctrl = true)
        val SelectAll = KeyboardShortcut(key = Key.A, ctrl = true)
        val Delete = KeyboardShortcut(key = Key.Delete)
        val Escape = KeyboardShortcut(key = Key.Escape)
        val Undo = KeyboardShortcut(key = Key.Z, ctrl = true)
        val Redo = KeyboardShortcut(key = Key.Y, ctrl = true)
    }
}

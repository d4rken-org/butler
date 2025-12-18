package eu.darken.butler.common.keyboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag

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

        val matches = event.key == key &&
            event.isCtrlPressed == ctrl &&
            event.isAltPressed == alt &&
            event.isShiftPressed == shift

        if (matches) log(TAG, INFO) { "Shortcut matched: $this" }

        return matches
    }

    companion object {
        private val TAG = logTag("Keyboard", "Shortcut")

        // Common shortcuts
        val Copy = KeyboardShortcut(key = Key.C, ctrl = true)
        val Cut = KeyboardShortcut(key = Key.X, ctrl = true)
        val Paste = KeyboardShortcut(key = Key.V, ctrl = true)
        val SelectAll = KeyboardShortcut(key = Key.A, ctrl = true)
        val Delete = KeyboardShortcut(key = Key.Delete)
        val Escape = KeyboardShortcut(key = Key.Escape)
        val New = KeyboardShortcut(key = Key.N, ctrl = true)
        val F2 = KeyboardShortcut(key = Key.F2)
        val Enter = KeyboardShortcut(key = Key.Enter)
        val Backspace = KeyboardShortcut(key = Key.Backspace)

        // Navigation
        val ArrowUp = KeyboardShortcut(key = Key.DirectionUp)
        val ArrowDown = KeyboardShortcut(key = Key.DirectionDown)
        val ArrowLeft = KeyboardShortcut(key = Key.DirectionLeft)
        val ArrowRight = KeyboardShortcut(key = Key.DirectionRight)
        val Home = KeyboardShortcut(key = Key.MoveHome)
        val End = KeyboardShortcut(key = Key.MoveEnd)
    }
}

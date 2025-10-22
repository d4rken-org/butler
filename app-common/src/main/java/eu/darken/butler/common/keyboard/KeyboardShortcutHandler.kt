package eu.darken.butler.common.keyboard

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent

/**
 * Registers keyboard shortcuts for a composable.
 *
 * Note: Text fields and other input components will consume their own key events first,
 * so shortcuts won't interfere with text input. This modifier should be applied to
 * the root composable of a screen/page.
 *
 * Example:
 * ```
 * Box(
 *     modifier = Modifier.keyboardShortcuts {
 *         on(KeyboardShortcut.Copy) { viewModel.onCopy() }
 *         on(KeyboardShortcut.Cut) { viewModel.onCut() }
 *         on(KeyboardShortcut.Paste) { viewModel.onPaste() }
 *     }
 * )
 * ```
 */
fun Modifier.keyboardShortcuts(
    builder: KeyboardShortcutScope.() -> Unit,
): Modifier {
    val scope = KeyboardShortcutScope().apply(builder)

    return this.onKeyEvent { event ->
        scope.handle(event)
    }
}

/**
 * DSL scope for registering keyboard shortcuts.
 */
class KeyboardShortcutScope {
    private val shortcuts = mutableListOf<Pair<KeyboardShortcut, () -> Unit>>()

    /**
     * Register a keyboard shortcut with an action.
     *
     * @param shortcut The keyboard shortcut to listen for
     * @param action The action to perform when the shortcut is triggered
     */
    fun on(shortcut: KeyboardShortcut, action: () -> Unit) {
        shortcuts.add(shortcut to action)
    }

    /**
     * Handles a keyboard event by checking all registered shortcuts.
     *
     * @return true if a shortcut was matched and consumed, false otherwise
     */
    internal fun handle(event: KeyEvent): Boolean {
        for ((shortcut, action) in shortcuts) {
            if (shortcut.matches(event)) {
                action()
                return true // Consume the event
            }
        }
        return false // Let the event propagate
    }
}

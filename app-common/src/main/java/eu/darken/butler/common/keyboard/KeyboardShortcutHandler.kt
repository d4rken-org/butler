package eu.darken.butler.common.keyboard

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag

/**
 * Registers keyboard shortcuts for a composable and automatically makes it focusable.
 *
 * This modifier automatically applies `.focusable()` to ensure the composable can receive
 * keyboard events, and requests focus when the composable is first displayed.
 *
 * Text fields and other input components will consume their own key events first,
 * so shortcuts won't interfere with text input.
 *
 * This modifier should be applied to the root composable of a screen/page.
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
@Composable
fun Modifier.keyboardShortcuts(
    builder: KeyboardShortcutScope.() -> Unit,
): Modifier {
    val scope = KeyboardShortcutScope().apply(builder)
    val focusRequester = remember { FocusRequester() }

    // Request focus when composable is first shown
    LaunchedEffect(Unit) {
        log(TAG, INFO) { "Requesting keyboard focus for shortcuts" }
        focusRequester.requestFocus()
    }

    return this
        .focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { event ->            scope.handle(event)        }
}

private val TAG = logTag("Keyboard", "Handler")

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
        log(TAG, VERBOSE) { "KeyEvent received: key=${event.key}, type=${event.type}, ctrl=${event.isCtrlPressed}, alt=${event.isAltPressed}, shift=${event.isShiftPressed}" }

        for ((shortcut, action) in shortcuts) {
            if (shortcut.matches(event)) {
                action()
                return true // Consume the event
            }
        }

        log(TAG, VERBOSE) { "No shortcut matched - event propagating" }
        return false // Let the event propagate
    }

    companion object {
        private val TAG = logTag("Keyboard", "Shortcuts")
    }
}

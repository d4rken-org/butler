package eu.darken.butler.workspace.ui.dialogs

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress

/** Presses and releases [keyCode] through the focused node, e.g. `KEYCODE_TAB` with `META_SHIFT_ON`. */
internal fun ComposeContentTestRule.pressDialogKey(keyCode: Int, metaState: Int = 0) {
    listOf(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.ACTION_UP).forEach { action ->
        onRoot().performKeyPress(KeyEvent(NativeKeyEvent(0L, 0L, action, keyCode, 0, metaState)))
    }
}

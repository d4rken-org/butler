package eu.darken.butler.common.compose

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.R
import kotlinx.coroutines.launch

/**
 * Copies plain text to the system clipboard and confirms it with a haptic tick.
 *
 * Wraps [LocalClipboard] so call sites stay free of `ClipData` and coroutine plumbing:
 * `Clipboard.setClipEntry` is a suspend function and there is no `AnnotatedString` overload, so
 * every call site would otherwise repeat the same scope-plus-ClipData dance. The clip is labelled
 * with the app name, matching [eu.darken.butler.common.SystemClipboardHelper] so the Compose and
 * non-Compose paths look identical in the system clipboard UI.
 *
 * The write is asynchronous and runs in the calling composable's scope. That is fine for a copy
 * triggered from a control that stays on screen, but a call site that dismisses itself in the same
 * click must copy *before* dismissing — otherwise the scope is cancelled and the write is lost.
 *
 * No in-app confirmation is shown: Android 13+ surfaces its own "Copied" notice, so a snackbar here
 * would duplicate it.
 */
@Composable
fun rememberClipboardCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.app_name)

    return remember(clipboard, haptics, scope, label) {
        { text ->
            // Fired here rather than inside the coroutine: the tick belongs to the tap, so it should
            // not wait on a binder round-trip, nor be skipped if the scope is cancelled mid-write.
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(label, text).toClipEntry())
            }
        }
    }
}

package eu.darken.butler.common.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * Strategy for turning an alert dialog description into an actual dialog.
 *
 * Exists so shared components in this module — [eu.darken.butler.common.error.ErrorDialog] above all
 * — can render pane-bound inside a workspace pane without this module depending on the workspace
 * layer. The workspace layer provides its own implementation through [LocalAlertDialogRenderer];
 * everything outside a pane keeps the platform window dialog.
 *
 * The slots mirror Material's `AlertDialog` so an implementation can delegate to either it or to a
 * pane-bound equivalent without the call site knowing which one it got.
 */
interface AlertDialogRenderer {

    /**
     * @param includeImePadding pad the dialog above the soft keyboard. Only meaningful for a dialog
     *        that draws inside the window; a platform window dialog is positioned by the system and
     *        ignores it.
     */
    @Composable
    fun Render(
        onDismissRequest: () -> Unit,
        confirmButton: @Composable () -> Unit,
        modifier: Modifier,
        dismissButton: (@Composable () -> Unit)?,
        icon: (@Composable () -> Unit)?,
        title: (@Composable () -> Unit)?,
        text: (@Composable () -> Unit)?,
        properties: DialogProperties,
        includeImePadding: Boolean,
    )
}

/** Material's platform window dialog: covers the whole screen, blocks every pane. */
object WindowAlertDialogRenderer : AlertDialogRenderer {

    @Composable
    override fun Render(
        onDismissRequest: () -> Unit,
        confirmButton: @Composable () -> Unit,
        modifier: Modifier,
        dismissButton: (@Composable () -> Unit)?,
        icon: (@Composable () -> Unit)?,
        title: (@Composable () -> Unit)?,
        text: (@Composable () -> Unit)?,
        properties: DialogProperties,
        includeImePadding: Boolean,
    ) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            properties = properties,
        )
    }
}

/**
 * Not a `staticCompositionLocalOf`: the workspace layer swaps this per pane subtree, and a static
 * local would recompose the whole tree below the provider on every change.
 */
val LocalAlertDialogRenderer = compositionLocalOf<AlertDialogRenderer> { WindowAlertDialogRenderer }

/**
 * Alert dialog that renders however the surrounding composition says it should: pane-bound inside a
 * workspace pane, a platform window dialog anywhere else.
 */
@Composable
fun AdaptiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    includeImePadding: Boolean = false,
) {
    LocalAlertDialogRenderer.current.Render(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        properties = properties,
        includeImePadding = includeImePadding,
    )
}

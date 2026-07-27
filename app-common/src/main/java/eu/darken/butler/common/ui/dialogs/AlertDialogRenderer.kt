package eu.darken.butler.common.ui.dialogs

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
 * Both implementations share one content shell ([ButlerAlertDialogContent]), so the slots are the
 * same set either way and a call site never has to know which host it got.
 */
interface AlertDialogRenderer {

    /**
     * @param neutralButton action placed at the *start* of the action row, away from confirm and
     *        dismiss. Material's `AlertDialog` has no equivalent slot; both hosts here do.
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
        neutralButton: (@Composable () -> Unit)?,
        icon: (@Composable () -> Unit)?,
        title: (@Composable () -> Unit)?,
        text: (@Composable () -> Unit)?,
        properties: DialogProperties,
        includeImePadding: Boolean,
    )
}

/** Platform window dialog: covers the whole screen, blocks every pane. */
object WindowAlertDialogRenderer : AlertDialogRenderer {

    @Composable
    override fun Render(
        onDismissRequest: () -> Unit,
        confirmButton: @Composable () -> Unit,
        modifier: Modifier,
        dismissButton: (@Composable () -> Unit)?,
        neutralButton: (@Composable () -> Unit)?,
        icon: (@Composable () -> Unit)?,
        title: (@Composable () -> Unit)?,
        text: (@Composable () -> Unit)?,
        properties: DialogProperties,
        includeImePadding: Boolean,
    ) {
        ButlerAlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            neutralButton = neutralButton,
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
    neutralButton: (@Composable () -> Unit)? = null,
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
        neutralButton = neutralButton,
        icon = icon,
        title = title,
        text = text,
        properties = properties,
        includeImePadding = includeImePadding,
    )
}

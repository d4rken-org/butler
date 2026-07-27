package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import eu.darken.butler.common.ui.dialogs.AlertDialogRenderer

/**
 * Renders shared alert dialogs as [PaneBoundAlertDialog], so a dialog raised by one pane leaves the
 * other panes visible and interactive.
 *
 * Provided by [eu.darken.butler.workspace.ui.modal.PaneLayerHost], which means it only applies
 * inside a pane. Like every pane-bound dialog it must be composed from a position that spans the
 * pane at overlay rank — a caller sitting in the pane's content slot would get a scrim confined to
 * the inset content bounds and stacked below any open sheet.
 */
object PaneBoundAlertDialogRenderer : AlertDialogRenderer {

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
        PaneBoundAlertDialog(
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
}

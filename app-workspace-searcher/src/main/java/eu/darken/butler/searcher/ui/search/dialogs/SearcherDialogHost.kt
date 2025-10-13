package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.workspace.ui.dialogs.DeleteConfirmationDialog
import eu.darken.butler.workspace.ui.dialogs.DeleteConfirmationResult

@Composable
fun SearcherDialogHost(
    dialogState: SearcherDialogState,
    onDismiss: () -> Unit,
    onDeleteConfirmed: (DeleteConfirmationResult) -> Unit,
    modifier: Modifier = Modifier
) {
    when (dialogState) {
        is SearcherDialogState.None -> {
            // No dialog to show
        }
        is SearcherDialogState.DeleteConfirmation -> {
            DeleteConfirmationDialog(
                items = dialogState.paths,
                onDismiss = onDismiss,
                onConfirm = onDeleteConfirmed
            )
        }
    }
}

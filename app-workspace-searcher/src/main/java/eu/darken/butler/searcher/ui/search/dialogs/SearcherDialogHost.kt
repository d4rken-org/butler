package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.ui.dialogs.DeleteConfirmationDialog

@Composable
fun SearcherDialogHost(
    modifier: Modifier = Modifier,
    dialogState: SearcherDialogState,
    onDismiss: () -> Unit,
    onDeleteConfirmed: (items: Set<APath<*>>) -> Unit,
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

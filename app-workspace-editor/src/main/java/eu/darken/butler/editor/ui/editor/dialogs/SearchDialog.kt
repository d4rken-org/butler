package eu.darken.butler.editor.ui.editor.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R

@Composable
fun SearchDialog(
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_dialog_search_title)) },
        text = {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.editor_dialog_search_label)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSearch(searchQuery) },
                enabled = searchQuery.isNotEmpty()
            ) {
                Text(stringResource(R.string.editor_action_search))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_dialog_action_cancel))
            }
        }
    )
}

@Preview2
@Composable
private fun SearchDialogPreview() {
    PreviewWrapper {
        SearchDialog(
            onSearch = {},
            onDismiss = {},
        )
    }
}

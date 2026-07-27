package eu.darken.butler.history.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.history.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog

@Composable
fun PathScopeDialog(
    initialPath: String?,
    onDismiss: () -> Unit,
    onApply: (String?) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(initialPath.orEmpty()) }

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        includeImePadding = true,
        title = { Text(stringResource(R.string.history_path_scope_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.history_path_scope_dialog_description),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.history_path_scope_dialog_input_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(input.trim().takeIf { it.isNotBlank() }) }) {
                Text(stringResource(R.string.history_path_scope_dialog_apply_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_path_scope_dialog_cancel_action))
            }
        },
    )
}

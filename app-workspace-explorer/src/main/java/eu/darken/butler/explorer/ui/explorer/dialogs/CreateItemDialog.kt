package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.explorer.R
import eu.darken.butler.common.R as CommonR

enum class CreateItemType {
    FILE,
    FOLDER
}

data class CreateItemResult(
    val name: String,
    val type: CreateItemType,
)

@Composable
fun CreateItemDialog(
    onValidate: (String) -> FilenameValidator.ValidationResult = { FilenameValidator.ValidationResult.Valid },
    onDismiss: () -> Unit,
    onConfirm: (CreateItemResult) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(CreateItemType.FOLDER) }
    val focusRequester = remember { FocusRequester() }
    val validation = remember(name) { onValidate(name) }
    val isError = validation is FilenameValidator.ValidationResult.Invalid

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.explorer_dialog_create_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.explorer_dialog_name_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        {
                            val chars = (validation as FilenameValidator.ValidationResult.Invalid).invalidChars.joinToString(" ")
                            Text(stringResource(CommonR.string.general_filename_validation_error, chars))
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (name.isNotBlank() && !isError) {
                                onConfirm(CreateItemResult(name, selectedType))
                            }
                        }
                    ),
                )
                
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.explorer_dialog_type_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (selectedType == CreateItemType.FOLDER),
                                onClick = { selectedType = CreateItemType.FOLDER },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedType == CreateItemType.FOLDER),
                            onClick = null
                        )
                        Text(
                            text = stringResource(R.string.explorer_dialog_type_folder),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (selectedType == CreateItemType.FILE),
                                onClick = { selectedType = CreateItemType.FILE },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedType == CreateItemType.FILE),
                            onClick = null
                        )
                        Text(
                            text = stringResource(R.string.explorer_dialog_type_file),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && !isError) {
                        onConfirm(CreateItemResult(name, selectedType))
                    }
                },
                enabled = name.isNotBlank() && !isError
            ) {
                Text(stringResource(CommonR.string.general_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        }
    )
}

@Preview2
@Composable
fun CreateItemDialogPreview() {
    PreviewWrapper {
        CreateItemDialog(
            onDismiss = {},
            onConfirm = {}
        )
    }
}
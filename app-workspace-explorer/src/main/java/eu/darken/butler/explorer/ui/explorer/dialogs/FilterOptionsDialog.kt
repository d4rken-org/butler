package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.FileTypeFilter

@Composable
fun FilterOptionsDialog(
    includePattern: String,
    excludePattern: String,
    fileTypeFilter: FileTypeFilter,
    useRegexPatterns: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (FilterOptionsResult) -> Unit,
) {
    var currentIncludePattern by remember { mutableStateOf(includePattern) }
    var currentExcludePattern by remember { mutableStateOf(excludePattern) }
    var currentFileTypeFilter by remember { mutableStateOf(fileTypeFilter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.explorer_action_filter))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.explorer_filter_file_type_label),
                    style = MaterialTheme.typography.titleSmall,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(modifier = Modifier.selectableGroup()) {
                    FileTypeFilter.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = currentFileTypeFilter == option,
                                    onClick = { currentFileTypeFilter = option },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = currentFileTypeFilter == option,
                                onClick = null,
                            )
                            Text(
                                text = when (option) {
                                    FileTypeFilter.ALL -> stringResource(R.string.explorer_filter_type_all)
                                    FileTypeFilter.FILES_ONLY -> stringResource(R.string.explorer_filter_type_files)
                                    FileTypeFilter.FOLDERS_ONLY -> stringResource(R.string.explorer_filter_type_folders)
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = currentIncludePattern,
                    onValueChange = { currentIncludePattern = it },
                    label = { Text(stringResource(R.string.explorer_filter_include_label)) },
                    placeholder = {
                        Text(
                            if (useRegexPatterns) {
                                stringResource(R.string.explorer_filter_include_placeholder_regex)
                            } else {
                                stringResource(R.string.explorer_filter_include_placeholder_simple)
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = currentExcludePattern,
                    onValueChange = { currentExcludePattern = it },
                    label = { Text(stringResource(R.string.explorer_filter_exclude_label)) },
                    placeholder = {
                        Text(
                            if (useRegexPatterns) {
                                stringResource(R.string.explorer_filter_exclude_placeholder_regex)
                            } else {
                                stringResource(R.string.explorer_filter_exclude_placeholder_simple)
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!useRegexPatterns) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.explorer_filter_regex_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        onConfirm(
                            FilterOptionsResult(
                                includePattern = "",
                                excludePattern = "",
                                fileTypeFilter = FileTypeFilter.ALL,
                            )
                        )
                    }
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_reset_action))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onConfirm(
                            FilterOptionsResult(
                                includePattern = currentIncludePattern,
                                excludePattern = currentExcludePattern,
                                fileTypeFilter = currentFileTypeFilter,
                            )
                        )
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        },
        dismissButton = null
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FilterOptionsDialogPreview() {
    FilterOptionsDialog(
        includePattern = "",
        excludePattern = "",
        fileTypeFilter = FileTypeFilter.ALL,
        useRegexPatterns = false,
        onDismiss = {},
        onConfirm = {},
    )
}
package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog

@Composable
fun SortOptionsDialog(
    currentSortSettings: SortSettings,
    onDismiss: () -> Unit,
    onConfirm: (SortOptionsResult) -> Unit,
) {
    var selectedMode by remember { mutableStateOf(currentSortSettings.mode) }
    var isReversed by remember { mutableStateOf(currentSortSettings.reversed) }

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.explorer_action_sort))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.explorer_sort_mode_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Column(
                    modifier = Modifier.selectableGroup()
                ) {
                    SortSettings.Mode.entries.forEach { mode ->
                        val isSelected = selectedMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    onClick = { selectedMode = mode },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null
                            )
                            Text(
                                text = stringResource(getSortModeLabel(mode)),
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.explorer_sort_descending_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isReversed,
                        onCheckedChange = { isReversed = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        SortOptionsResult(
                            sortSettings = SortSettings(
                                mode = selectedMode,
                                reversed = isReversed
                            )
                        )
                    )
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

private fun getSortModeLabel(mode: SortSettings.Mode): Int = when (mode) {
    SortSettings.Mode.NAME -> R.string.explorer_sort_mode_name_label
    SortSettings.Mode.SIZE -> R.string.explorer_sort_mode_size_label
    SortSettings.Mode.MODIFIED_AT -> R.string.explorer_sort_mode_modified_label
    SortSettings.Mode.CREATED_AT -> R.string.explorer_sort_mode_created_label
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SortOptionsDialogPreview() {
    SortOptionsDialog(
        currentSortSettings = SortSettings(
            mode = SortSettings.Mode.NAME,
            reversed = false
        ),
        onDismiss = {},
        onConfirm = {}
    )
}
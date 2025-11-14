package eu.darken.butler.searcher.ui.search.dialogs

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
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchSortSettings

@Composable
fun SearchSortOptionsDialog(
    currentSortSettings: SearchSortSettings,
    onDismiss: () -> Unit,
    onConfirm: (SearchSortOptionsResult) -> Unit,
) {
    var selectedMode by remember { mutableStateOf(currentSortSettings.mode) }
    var isReversed by remember { mutableStateOf(currentSortSettings.reversed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.searcher_action_sort))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.searcher_sort_mode_label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Column(
                    modifier = Modifier.selectableGroup()
                ) {
                    SearchSortSettings.Mode.entries.forEach { mode ->
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
                        text = stringResource(R.string.searcher_sort_descending_label),
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
                        SearchSortOptionsResult(
                            sortSettings = SearchSortSettings(
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

private fun getSortModeLabel(mode: SearchSortSettings.Mode): Int = when (mode) {
    SearchSortSettings.Mode.NAME -> R.string.searcher_sort_mode_name_label
    SearchSortSettings.Mode.SIZE -> R.string.searcher_sort_mode_size_label
    SearchSortSettings.Mode.MODIFIED_AT -> R.string.searcher_sort_mode_modified_label
    SearchSortSettings.Mode.CREATED_AT -> R.string.searcher_sort_mode_created_label
    SearchSortSettings.Mode.PATH -> R.string.searcher_sort_mode_path_label
}

data class SearchSortOptionsResult(
    val sortSettings: SearchSortSettings
)

@Preview2
@Composable
private fun SearchSortOptionsDialogPreview() {
    PreviewWrapper {
        SearchSortOptionsDialog(
            currentSortSettings = SearchSortSettings(
                mode = SearchSortSettings.Mode.NAME,
                reversed = false
            ),
            onDismiss = {},
            onConfirm = {}
        )
    }
}

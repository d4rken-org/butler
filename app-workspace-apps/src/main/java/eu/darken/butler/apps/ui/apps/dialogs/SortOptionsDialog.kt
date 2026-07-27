package eu.darken.butler.apps.ui.apps.dialogs

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog

@Composable
fun SortOptionsDialog(
    currentSortSettings: SortSettings,
    onDismiss: () -> Unit,
    onApply: (SortSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedMode by rememberSaveable(currentSortSettings) { mutableStateOf(currentSortSettings.mode) }
    var isReversed by rememberSaveable(currentSortSettings) { mutableStateOf(currentSortSettings.reversed) }

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.apps_action_sort))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.apps_sort_mode_label),
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
                        text = stringResource(R.string.apps_sort_descending_label),
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
                    onApply(
                        SortSettings(
                            mode = selectedMode,
                            reversed = isReversed
                        )
                    )
                }
            ) {
                Text(stringResource(CommonR.string.general_apply_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        modifier = modifier,
    )
}

private fun getSortModeLabel(mode: SortSettings.Mode): Int = when (mode) {
    SortSettings.Mode.NAME -> R.string.apps_sort_mode_name_label
    SortSettings.Mode.SIZE -> R.string.apps_sort_mode_size_label
    SortSettings.Mode.INSTALL_DATE -> R.string.apps_sort_mode_install_date_label
    SortSettings.Mode.UPDATE_DATE -> R.string.apps_sort_mode_update_date_label
    SortSettings.Mode.PACKAGE_NAME -> R.string.apps_sort_mode_package_name_label
}

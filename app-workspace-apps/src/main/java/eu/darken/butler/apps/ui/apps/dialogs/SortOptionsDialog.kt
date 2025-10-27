package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.engine.SortMode

@Composable
fun SortOptionsDialog(
    currentSortMode: SortMode,
    onDismiss: () -> Unit,
    onApply: (SortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSortMode by remember { mutableStateOf(currentSortMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Sort apps")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SortMode.entries.forEach { sortMode ->
                    val label = when (sortMode) {
                        SortMode.NAME_ASC -> stringResource(R.string.apps_sort_name_asc)
                        SortMode.NAME_DESC -> stringResource(R.string.apps_sort_name_desc)
                        SortMode.SIZE_ASC -> stringResource(R.string.apps_sort_size_asc)
                        SortMode.SIZE_DESC -> stringResource(R.string.apps_sort_size_desc)
                        SortMode.INSTALL_DATE -> stringResource(R.string.apps_sort_install_date)
                        SortMode.UPDATE_DATE -> stringResource(R.string.apps_sort_update_date)
                        SortMode.PACKAGE_NAME -> stringResource(R.string.apps_sort_package_name)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSortMode = sortMode }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedSortMode == sortMode,
                            onClick = { selectedSortMode = sortMode }
                        )
                        Text(
                            text = label,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(selectedSortMode)
                    onDismiss()
                }
            ) {
                Text(text = "Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        },
        modifier = modifier,
    )
}

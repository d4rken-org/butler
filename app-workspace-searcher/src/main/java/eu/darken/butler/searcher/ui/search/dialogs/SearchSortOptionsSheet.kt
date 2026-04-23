package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

@Composable
fun SearchSortOptionsSheet(
    modifier: Modifier = Modifier,
    visible: Boolean,
    currentSortSettings: SearchSortSettings,
    onDismiss: () -> Unit,
    onConfirm: (SearchSortOptionsResult) -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        modifier = modifier,
        visible = visible,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
    ) {
        SearchSortOptionsContent(
            currentSortSettings = currentSortSettings,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

@Composable
private fun SearchSortOptionsContent(
    currentSortSettings: SearchSortSettings,
    onDismiss: () -> Unit,
    onConfirm: (SearchSortOptionsResult) -> Unit,
) {
    var selectedMode by remember { mutableStateOf(currentSortSettings.mode) }
    var isReversed by remember { mutableStateOf(currentSortSettings.reversed) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        // Header
        Text(
            text = stringResource(R.string.searcher_action_sort),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Sort mode label
        Text(
            text = stringResource(R.string.searcher_sort_mode_label),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Sort mode radio buttons
        Column(modifier = Modifier.selectableGroup()) {
            SearchSortSettings.Mode.entries.forEach { mode ->
                val isSelected = selectedMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            onClick = { selectedMode = mode },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                    )
                    Text(
                        text = stringResource(getSortModeLabel(mode)),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reverse toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.searcher_sort_descending_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = isReversed,
                onCheckedChange = { isReversed = it },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
            }
            Button(
                onClick = {
                    onConfirm(
                        SearchSortOptionsResult(
                            sortSettings = SearchSortSettings(
                                mode = selectedMode,
                                reversed = isReversed,
                            )
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(eu.darken.butler.common.R.string.general_apply_action))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun getSortModeLabel(mode: SearchSortSettings.Mode): Int = when (mode) {
    SearchSortSettings.Mode.NAME -> R.string.searcher_sort_mode_name_label
    SearchSortSettings.Mode.SIZE -> R.string.searcher_sort_mode_size_label
    SearchSortSettings.Mode.MODIFIED_AT -> R.string.searcher_sort_mode_modified_label
    SearchSortSettings.Mode.CREATED_AT -> R.string.searcher_sort_mode_created_label
    SearchSortSettings.Mode.PATH -> R.string.searcher_sort_mode_path_label
}

data class SearchSortOptionsResult(
    val sortSettings: SearchSortSettings,
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchSortOptionsSheetPreview() {
    SearchSortOptionsSheet(
        visible = true,
        currentSortSettings = SearchSortSettings(
            mode = SearchSortSettings.Mode.NAME,
            reversed = false,
        ),
        onDismiss = {},
        onConfirm = {},
    )
}

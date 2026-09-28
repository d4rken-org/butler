package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.OpenSelectionMode
import eu.darken.butler.common.R as CommonR

@Composable
fun OpenSelectionDialog(
    itemCount: Int,
    viewerModesAvailable: Boolean,
    onDismiss: () -> Unit,
    onSelect: (OpenSelectionMode) -> Unit,
) {
    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = pluralStringResource(R.plurals.workspace_open_selection_title, itemCount, itemCount),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OpenSelectionOption(
                    title = stringResource(R.string.workspace_open_selection_view_here_action),
                    description = stringResource(R.string.workspace_open_selection_view_here_description),
                    enabled = viewerModesAvailable,
                    onClick = { onSelect(OpenSelectionMode.VIEW_HERE) },
                )
                OpenSelectionOption(
                    title = stringResource(R.string.workspace_open_selection_view_in_tab_action),
                    description = stringResource(R.string.workspace_open_selection_view_in_tab_description),
                    enabled = viewerModesAvailable,
                    onClick = { onSelect(OpenSelectionMode.VIEW_IN_TAB) },
                )
                if (!viewerModesAvailable) {
                    Text(
                        text = stringResource(R.string.workspace_open_selection_viewer_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                OpenSelectionOption(
                    title = stringResource(R.string.workspace_open_selection_each_in_tab_action),
                    description = stringResource(R.string.workspace_open_selection_each_in_tab_description),
                    enabled = true,
                    onClick = { onSelect(OpenSelectionMode.EACH_IN_TAB) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Composable
private fun OpenSelectionOption(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else disabledColor,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OpenSelectionDialogPreview() {
    OpenSelectionDialog(
        itemCount = 3,
        viewerModesAvailable = true,
        onDismiss = {},
        onSelect = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OpenSelectionDialogViewerUnavailablePreview() {
    OpenSelectionDialog(
        itemCount = 4,
        viewerModesAvailable = false,
        onDismiss = {},
        onSelect = {},
    )
}

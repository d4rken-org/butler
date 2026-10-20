package eu.darken.butler.explorer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asPathStyle
import eu.darken.butler.common.files.extensions.shownPath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.DefaultStartLocation

@Composable
internal fun StartLocationDialog(
    dialog: ExplorerSettingsViewModel.StartLocationDialog,
    onSelect: (DefaultStartLocation) -> Unit,
    onChooseFolder: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_settings_start_location_title)) },
        text = {
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StartLocationOption(
                    label = stringResource(R.string.explorer_settings_start_location_home_default),
                    selected = dialog.selection is DefaultStartLocation.Home,
                    onClick = { onSelect(DefaultStartLocation.Home) },
                )
                StartLocationOption(
                    label = stringResource(R.string.explorer_navigation_device),
                    selected = dialog.selection is DefaultStartLocation.Device,
                    onClick = { onSelect(DefaultStartLocation.Device) },
                )
                dialog.folder?.let { folder ->
                    StartLocationOption(
                        label = stringResource(R.string.explorer_settings_start_location_folder_label),
                        path = folder.shownPath.get(context),
                        selected = dialog.selection is DefaultStartLocation.Directory,
                        onClick = { onSelect(DefaultStartLocation.Directory(folder)) },
                    )
                }
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    onClick = onChooseFolder,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Folder,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(R.string.explorer_settings_start_location_choose_folder_action))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(eu.darken.butler.common.R.string.general_save_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
            }
        },
    )
}

@Composable
private fun StartLocationOption(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    path: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (path != null) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall.asPathStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StartLocationDialogPreview() {
    val folder = LocalPath.build("/storage/emulated/0/Download")
    StartLocationDialog(
        dialog = ExplorerSettingsViewModel.StartLocationDialog(
            id = 0,
            selection = DefaultStartLocation.Directory(folder),
            folder = folder,
        ),
        onSelect = {},
        onChooseFolder = {},
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StartLocationDialogNoFolderPreview() {
    StartLocationDialog(
        dialog = ExplorerSettingsViewModel.StartLocationDialog(
            id = 0,
            selection = DefaultStartLocation.Home,
            folder = null,
        ),
        onSelect = {},
        onChooseFolder = {},
        onConfirm = {},
        onDismiss = {},
    )
}

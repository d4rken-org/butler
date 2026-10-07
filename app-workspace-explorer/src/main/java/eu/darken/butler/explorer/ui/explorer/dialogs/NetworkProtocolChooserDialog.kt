package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.common.R as CommonR

@Composable
fun NetworkProtocolChooserDialog(
    modifier: Modifier = Modifier,
    onChoose: (NetworkProtocol) -> Unit,
    onDismiss: () -> Unit,
) {
    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_network_protocol_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ProtocolRow(
                    icon = Icons.TwoTone.FolderShared,
                    label = stringResource(R.string.explorer_network_protocol_smb_label),
                    caption = stringResource(R.string.explorer_network_protocol_smb_caption),
                    onClick = { onChoose(NetworkProtocol.SMB) },
                )
                ProtocolRow(
                    icon = Icons.TwoTone.Terminal,
                    label = stringResource(R.string.explorer_network_protocol_sftp_label),
                    caption = stringResource(R.string.explorer_network_protocol_sftp_caption),
                    onClick = { onChoose(NetworkProtocol.SFTP) },
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
private fun ProtocolRow(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    caption: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        headlineContent = { Text(label) },
        supportingContent = { Text(caption) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun NetworkProtocolChooserDialogPreview() {
    NetworkProtocolChooserDialog(
        onChoose = {},
        onDismiss = {},
    )
}

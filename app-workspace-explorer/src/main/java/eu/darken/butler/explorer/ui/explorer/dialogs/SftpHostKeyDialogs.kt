package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.GppMaybe
import androidx.compose.material.icons.twotone.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import kotlin.uuid.Uuid
import eu.darken.butler.common.R as CommonR

/** Asks whether the key a new endpoint presented is the one it should have. */
@Composable
fun SftpHostKeyConfirmationDialog(
    modifier: Modifier = Modifier,
    confirmation: SftpHostKeyConfirmation,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        icon = { Icon(imageVector = Icons.TwoTone.VpnKey, contentDescription = null) },
        title = { Text(stringResource(R.string.explorer_sftp_host_key_unknown_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.explorer_sftp_host_key_unknown_message,
                        sftpEndpointLabel(confirmation.host, confirmation.port),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HostKeyDetails(
                    label = stringResource(R.string.explorer_sftp_host_key_type_label),
                    value = confirmation.presentedKey.type,
                )
                HostKeyDetails(
                    label = stringResource(R.string.explorer_sftp_host_key_fingerprint_label),
                    value = confirmation.presentedKey.fingerprint,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.explorer_sftp_host_key_accept_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

/** Asks whether the changed key of a stored server replaces the one confirmed for it. */
@Composable
fun SftpHostKeyRetrustDialog(
    modifier: Modifier = Modifier,
    confirmation: SftpRetrustConfirmation,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.TwoTone.GppMaybe,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.explorer_sftp_host_key_retrust_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.explorer_sftp_host_key_retrust_message,
                        sftpEndpointLabel(confirmation.host, confirmation.port),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HostKeyComparison(stored = confirmation.storedKey, presented = confirmation.presentedKey)
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(
                    text = stringResource(R.string.explorer_sftp_host_key_retrust_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

/** The confirmed and the presented key below each other, as the retrust dialog and the error card show them. */
@Composable
fun HostKeyComparison(
    modifier: Modifier = Modifier,
    stored: TrustedHostKey,
    presented: TrustedHostKey,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HostKeyDetails(
            label = stringResource(R.string.explorer_sftp_host_key_stored_label),
            value = "${stored.type} ${stored.fingerprint}",
        )
        HostKeyDetails(
            label = stringResource(R.string.explorer_sftp_host_key_presented_label),
            value = "${presented.type} ${presented.fingerprint}",
        )
    }
}

@Composable
private fun HostKeyDetails(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpHostKeyConfirmationDialogPreview() {
    SftpHostKeyConfirmationDialog(
        confirmation = SftpHostKeyConfirmation(
            host = "build.lan",
            port = 22,
            presentedKey = previewHostKey(1),
            formRevision = 0,
            keyGeneration = 0,
        ),
        onAccept = {},
        onCancel = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpHostKeyRetrustDialogPreview() {
    SftpHostKeyRetrustDialog(
        confirmation = SftpRetrustConfirmation(
            locationId = Uuid.parse("66666666-7777-8888-9999-000000000000"),
            host = "build.lan",
            port = 22,
            endpoint = "darken@build.lan/srv/builds",
            storedKey = previewHostKey(1),
            presentedKey = previewHostKey(2),
            trustRevision = 1,
        ),
        onAccept = {},
        onCancel = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HostKeyComparisonPreview() {
    HostKeyComparison(stored = previewHostKey(1), presented = previewHostKey(2))
}

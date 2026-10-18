package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.common.R as CommonR

/** Asks only for the passphrase of a stored key, see [ExplorerDialogState.SftpUnlock]. */
@Composable
fun SftpUnlockDialog(
    modifier: Modifier = Modifier,
    state: ExplorerDialogState.SftpUnlock,
    onUnlock: (String) -> Unit,
    onEditServer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var passphrase by remember(state.dialogId) { mutableStateOf("") }
    var passphraseVisible by remember { mutableStateOf(false) }
    val canUnlock = passphrase.isNotEmpty() && !state.isUnlocking
    val submit = {
        if (canUnlock) onUnlock(passphrase)
    }

    val layerActive = LocalLayerActive.current
    LaunchedEffect(layerActive) {
        if (layerActive) focusRequester.requestFocus()
    }

    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        includeImePadding = true,
        title = {
            Text(stringResource(R.string.explorer_sftp_unlock_title, state.location.displayName.get(context)))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.explorer_sftp_unlock_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.explorer_sftp_unlock_passphrase_label)) },
                    enabled = !state.isUnlocking,
                    singleLine = true,
                    visualTransformation = if (passphraseVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                            Icon(
                                imageVector = if (passphraseVisible) {
                                    Icons.TwoTone.VisibilityOff
                                } else {
                                    Icons.TwoTone.Visibility
                                },
                                contentDescription = stringResource(
                                    if (passphraseVisible) {
                                        R.string.explorer_info_network_password_hide_action
                                    } else {
                                        R.string.explorer_info_network_password_show_action
                                    }
                                ),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                state.error?.let { error ->
                    Text(
                        text = error.get(context),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.isUnlocking) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = stringResource(R.string.explorer_network_form_testing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canUnlock) {
                Text(stringResource(R.string.explorer_sftp_unlock_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isUnlocking) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
        neutralButton = {
            TextButton(onClick = onEditServer, enabled = !state.isUnlocking) {
                Text(stringResource(R.string.explorer_sftp_unlock_edit_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpUnlockDialogPreview() {
    SftpUnlockDialog(
        state = ExplorerDialogState.SftpUnlock(location = previewSftpLocation().copy(rememberPassphrase = false)),
        onUnlock = {},
        onEditServer = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpUnlockDialogUnlockingPreview() {
    SftpUnlockDialog(
        state = ExplorerDialogState.SftpUnlock(
            location = previewSftpLocation().copy(rememberPassphrase = false),
            isUnlocking = true,
        ),
        onUnlock = {},
        onEditServer = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpUnlockDialogErrorPreview() {
    SftpUnlockDialog(
        state = ExplorerDialogState.SftpUnlock(
            location = previewSftpLocation().copy(rememberPassphrase = false),
            error = "The passphrase does not unlock this private key".toCaString(),
        ),
        onUnlock = {},
        onEditServer = {},
        onDismiss = {},
    )
}

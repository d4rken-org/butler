package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material.icons.twotone.VisibilityOff
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.common.R as CommonR

/**
 * Asks for an archive password outside of an operation, e.g. while browsing into an archive whose
 * entry list is encrypted or opening an encrypted entry. Operations prompt through the issue sheet.
 */
@Composable
fun ArchiveUnlockDialog(
    issue: PathActionIssue.ArchivePasswordRequired,
    onDismiss: () -> Unit,
    onUnlock: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    val handleUnlock = {
        if (password.isNotEmpty()) onUnlock(password)
    }

    val layerActive = LocalLayerActive.current
    LaunchedEffect(layerActive) {
        if (layerActive) focusRequester.requestFocus()
    }

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        includeImePadding = true,
        title = {
            Text(
                text = issue.title.asComposable(),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = issue.description.asComposable(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (issue.attemptFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text(stringResource(R.string.workspace_issue_archive_password_label)) },
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { handleUnlock() }),
                    trailingIcon = {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                imageVector = if (revealed) Icons.TwoTone.VisibilityOff else Icons.TwoTone.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = handleUnlock,
                enabled = password.isNotEmpty(),
            ) {
                Text(stringResource(R.string.workspace_issue_archive_password_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchiveUnlockDialogPreview() {
    ArchiveUnlockDialog(
        issue = PathActionIssue.ArchivePasswordRequired(
            container = LocalPath.build("/storage/emulated/0/Download/photos.7z"),
        ),
        onDismiss = {},
        onUnlock = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchiveUnlockDialogWrongPasswordPreview() {
    ArchiveUnlockDialog(
        issue = PathActionIssue.ArchivePasswordRequired(
            container = LocalPath.build("/storage/emulated/0/Download/photos.7z"),
            attemptFailed = true,
        ),
        onDismiss = {},
        onUnlock = {},
    )
}

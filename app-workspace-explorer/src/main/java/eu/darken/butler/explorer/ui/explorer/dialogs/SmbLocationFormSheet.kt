package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.smb.SmbLocationInput
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.common.R as CommonR

/** Raw field contents, validated by [SmbLocationInput] before anything is stored. */
data class SmbLocationFormInput(
    val label: String,
    val host: String,
    val port: String,
    val share: String,
    val basePath: String,
    val authType: SmbLocation.AuthType,
    val username: String,
    val domain: String,
    val password: String,
    val rememberCredential: Boolean,
)

@Composable
fun SmbLocationFormSheet(
    state: ExplorerDialogState.SmbLocationForm,
    onDismiss: () -> Unit,
    onSubmit: (SmbLocationFormInput) -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    val context = LocalContext.current
    val existing = state.existing

    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.toString() ?: SmbLocationInput.DEFAULT_PORT.toString()) }
    var share by remember { mutableStateOf(existing?.share.orEmpty()) }
    var basePath by remember { mutableStateOf(existing?.basePath?.joinToString("/").orEmpty()) }
    var authType by remember { mutableStateOf(existing?.authType ?: SmbLocation.AuthType.PASSWORD) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var domain by remember { mutableStateOf(existing?.domain.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberCredential by remember { mutableStateOf(existing?.rememberCredential ?: true) }

    val usesPassword = authType == SmbLocation.AuthType.PASSWORD
    // Editing keeps the stored password only while the credential fields and the remember switch
    // stay put. The domain is part of the credential, so it counts too.
    val keepsStoredCredential = existing != null &&
        username == existing.username.orEmpty() &&
        SmbLocationInput.normalizeDomain(domain) == SmbLocationInput.normalizeDomain(existing.domain) &&
        rememberCredential == existing.rememberCredential
    val canSubmit = !state.isTesting &&
        host.isNotBlank() &&
        share.isNotBlank() &&
        (!usesPassword || password.isNotEmpty() || keepsStoredCredential)

    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (existing == null) R.string.explorer_network_form_add_title
                    else R.string.explorer_network_form_edit_title
                ),
                style = MaterialTheme.typography.headlineSmall,
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.explorer_network_form_label_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.explorer_network_form_host_label)) },
                placeholder = { Text(stringResource(R.string.explorer_network_form_host_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.explorer_network_form_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.4f),
                )

                OutlinedTextField(
                    value = share,
                    onValueChange = { share = it },
                    label = { Text(stringResource(R.string.explorer_network_form_share_label)) },
                    placeholder = { Text(stringResource(R.string.explorer_network_form_share_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(0.6f),
                )
            }

            OutlinedTextField(
                value = basePath,
                onValueChange = { basePath = it },
                label = { Text(stringResource(R.string.explorer_network_form_base_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authType == SmbLocation.AuthType.GUEST,
                    onClick = { authType = SmbLocation.AuthType.GUEST },
                    label = { Text(stringResource(R.string.explorer_network_form_auth_guest)) },
                )
                FilterChip(
                    selected = usesPassword,
                    onClick = { authType = SmbLocation.AuthType.PASSWORD },
                    label = { Text(stringResource(R.string.explorer_network_form_auth_password)) },
                )
            }

            if (usesPassword) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.explorer_network_form_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.explorer_network_form_domain_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.explorer_network_form_password_label)) },
                    supportingText = if (existing != null) {
                        { Text(stringResource(R.string.explorer_network_form_password_kept_hint)) }
                    } else null,
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.TwoTone.VisibilityOff
                                } else {
                                    Icons.TwoTone.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.explorer_network_form_remember_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.explorer_network_form_remember_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = rememberCredential,
                        onCheckedChange = { rememberCredential = it },
                    )
                }
            }

            state.error?.let { error ->
                Text(
                    text = error.get(context),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.explorer_network_form_testing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    enabled = !state.isTesting,
                ) {
                    Text(stringResource(CommonR.string.general_cancel_action))
                }

                Button(
                    onClick = {
                        onSubmit(
                            SmbLocationFormInput(
                                label = label,
                                host = host,
                                port = port,
                                share = share,
                                basePath = basePath,
                                authType = authType,
                                username = username,
                                domain = domain,
                                password = password,
                                rememberCredential = rememberCredential,
                            )
                        )
                    },
                    enabled = canSubmit,
                ) {
                    Text(stringResource(R.string.explorer_network_form_test_and_save_action))
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SmbLocationFormSheetAddPreview() {
    SmbLocationFormSheet(
        state = ExplorerDialogState.SmbLocationForm(),
        onDismiss = {},
        onSubmit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SmbLocationFormSheetEditPreview() {
    SmbLocationFormSheet(
        state = ExplorerDialogState.SmbLocationForm(existing = previewLocation()),
        onDismiss = {},
        onSubmit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SmbLocationFormSheetTestingPreview() {
    SmbLocationFormSheet(
        state = ExplorerDialogState.SmbLocationForm(existing = previewLocation(), isTesting = true),
        onDismiss = {},
        onSubmit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SmbLocationFormSheetErrorPreview() {
    SmbLocationFormSheet(
        state = ExplorerDialogState.SmbLocationForm(
            existing = previewLocation(),
            error = "nas.local rejected the username or password.".toCaString(),
        ),
        onDismiss = {},
        onSubmit = {},
    )
}

private fun previewLocation() = SmbLocation(
    id = kotlin.uuid.Uuid.parse("11111111-2222-3333-4444-555555555555"),
    label = "Home NAS",
    host = "nas.local",
    share = "media",
    username = "darken",
    authType = SmbLocation.AuthType.PASSWORD,
    rememberCredential = true,
    credentialVersion = 1,
    createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
)

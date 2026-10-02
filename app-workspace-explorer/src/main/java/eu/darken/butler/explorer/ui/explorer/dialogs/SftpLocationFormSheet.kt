package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Key
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.rememberIsPro
import eu.darken.butler.common.files.sftp.SftpLocationInput
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import eu.darken.butler.common.R as CommonR

/**
 * Raw field contents, validated by [SftpLocationInput] before anything is stored. [revision] counts
 * the edits made to the form, see [SftpHostKeyConfirmation].
 */
data class SftpLocationFormInput(
    val label: String,
    val host: String,
    val port: String,
    val username: String,
    val basePath: String,
    val authType: SftpLocation.AuthType,
    val password: String,
    val passphrase: String,
    val rememberCredential: Boolean,
    val revision: Int,
) {
    override fun toString(): String = "SftpLocationFormInput($host:$port, $authType, revision=$revision)"
}

/**
 * Whether a save may keep the stored secret: [eu.darken.butler.common.files.sftp.location.SftpLocationManager.update]
 * only allows that while these stay the same.
 */
fun SftpLocation.keepsCredentialFor(
    username: String,
    authType: SftpLocation.AuthType,
    rememberCredential: Boolean,
): Boolean = username == this.username && authType == this.authType && rememberCredential == this.rememberCredential

/** `build.lan:22`, `[fe80::1]:2222` */
fun sftpEndpointLabel(host: String, port: Int): String = if (':' in host) "[$host]:$port" else "$host:$port"

/**
 * The form's fields, kept outside the sheet so a dialog stacked above it can read them. Every
 * change bumps [revision].
 */
@Stable
class SftpLocationFormFields(existing: SftpLocation?) {

    var revision by mutableIntStateOf(0)
        private set

    private inner class Tracked<T>(initial: T) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(initial)

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (state.value == value) return
            state.value = value
            revision++
        }
    }

    var label by Tracked(existing?.label.orEmpty())
    var host by Tracked(existing?.host.orEmpty())
    var port by Tracked((existing?.port ?: SftpLocationInput.DEFAULT_PORT).toString())
    var username by Tracked(existing?.username.orEmpty())
    var basePath by Tracked(existing?.basePath.orEmpty())
    var authType by Tracked(existing?.authType ?: SftpLocation.AuthType.PASSWORD)
    var password by Tracked("")
    var passphrase by Tracked("")
    var rememberCredential by Tracked(existing?.rememberCredential ?: true)

    fun toInput() = SftpLocationFormInput(
        label = label,
        host = host,
        port = port,
        username = username,
        basePath = basePath,
        authType = authType,
        password = password,
        passphrase = passphrase,
        rememberCredential = rememberCredential,
        revision = revision,
    )
}

@Composable
fun rememberSftpLocationFormFields(state: ExplorerDialogState.SftpLocationForm): SftpLocationFormFields =
    remember(state.formId) { SftpLocationFormFields(state.existing) }

@Composable
fun SftpLocationFormSheet(
    modifier: Modifier = Modifier,
    state: ExplorerDialogState.SftpLocationForm,
    fields: SftpLocationFormFields = rememberSftpLocationFormFields(state),
    onDismiss: () -> Unit,
    onSubmit: (SftpLocationFormInput) -> Unit,
    onPickKeyFile: () -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    isPro: Boolean = rememberIsPro(),
) {
    val context = LocalContext.current
    val existing = state.existing
    val isBusy = state.isTesting || state.isReadingKey

    val usesPassword = fields.authType == SftpLocation.AuthType.PASSWORD
    val keepsStored = existing?.keepsCredentialFor(
        username = fields.username.trim(),
        authType = fields.authType,
        rememberCredential = fields.rememberCredential,
    ) == true
    // A sign-in prompt exists because the stored secret failed, so it always needs a fresh one.
    val isSignIn = state.mode == SftpFormMode.SIGN_IN
    val hasSecret = when (fields.authType) {
        SftpLocation.AuthType.PASSWORD -> fields.password.isNotEmpty() || (keepsStored && !isSignIn)
        SftpLocation.AuthType.PRIVATE_KEY -> state.keyFileName != null ||
            (keepsStored && (!isSignIn || fields.passphrase.isNotEmpty()))
    }
    val canSubmit = !isBusy &&
        state.hostKeyConfirmation == null &&
        state.retrustConfirmation == null &&
        fields.host.isNotBlank() &&
        fields.username.isNotBlank() &&
        hasSecret

    var secretVisible by remember { mutableStateOf(false) }

    PaneScopedBottomSheet(
        modifier = modifier,
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        includeImePadding = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when (state.mode) {
                    SftpFormMode.ADD -> stringResource(R.string.explorer_sftp_form_add_title)
                    SftpFormMode.EDIT -> stringResource(R.string.explorer_sftp_form_edit_title)
                    SftpFormMode.SIGN_IN -> stringResource(
                        R.string.explorer_sftp_form_sign_in_title,
                        existing?.displayName?.get(context).orEmpty(),
                    )
                },
                style = MaterialTheme.typography.headlineSmall,
            )

            if (isSignIn) {
                Text(
                    text = stringResource(
                        if (usesPassword) R.string.explorer_sftp_form_sign_in_password_hint
                        else R.string.explorer_sftp_form_sign_in_key_hint
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = fields.label,
                onValueChange = { fields.label = it },
                label = { Text(stringResource(R.string.explorer_network_form_label_label)) },
                enabled = !isBusy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = fields.host,
                onValueChange = { fields.host = it },
                label = { Text(stringResource(R.string.explorer_network_form_host_label)) },
                placeholder = { Text(stringResource(R.string.explorer_sftp_form_host_hint)) },
                enabled = !isBusy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fields.port,
                    onValueChange = { fields.port = it },
                    label = { Text(stringResource(R.string.explorer_network_form_port_label)) },
                    enabled = !isBusy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.35f),
                )

                OutlinedTextField(
                    value = fields.username,
                    onValueChange = { fields.username = it },
                    label = { Text(stringResource(R.string.explorer_network_form_username_label)) },
                    enabled = !isBusy,
                    singleLine = true,
                    modifier = Modifier.weight(0.65f),
                )
            }

            OutlinedTextField(
                value = fields.basePath,
                onValueChange = { fields.basePath = it },
                label = { Text(stringResource(R.string.explorer_sftp_form_base_path_label)) },
                supportingText = { Text(stringResource(R.string.explorer_sftp_form_base_path_help)) },
                enabled = !isBusy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = usesPassword,
                    onClick = { fields.authType = SftpLocation.AuthType.PASSWORD },
                    label = { Text(stringResource(R.string.explorer_sftp_form_auth_password)) },
                    enabled = !isBusy,
                )
                FilterChip(
                    selected = !usesPassword,
                    onClick = { fields.authType = SftpLocation.AuthType.PRIVATE_KEY },
                    label = { Text(stringResource(R.string.explorer_sftp_form_auth_private_key)) },
                    enabled = !isBusy,
                )
            }

            val secretTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation()
            val secretToggle: @Composable () -> Unit = {
                IconButton(onClick = { secretVisible = !secretVisible }) {
                    Icon(
                        imageVector = if (secretVisible) Icons.TwoTone.VisibilityOff else Icons.TwoTone.Visibility,
                        contentDescription = stringResource(
                            if (secretVisible) R.string.explorer_info_network_password_hide_action
                            else R.string.explorer_info_network_password_show_action
                        ),
                    )
                }
            }

            if (usesPassword) {
                OutlinedTextField(
                    value = fields.password,
                    onValueChange = { fields.password = it },
                    label = { Text(stringResource(R.string.explorer_network_form_password_label)) },
                    supportingText = if (keepsStored && !isSignIn) {
                        { Text(stringResource(R.string.explorer_network_form_password_kept_hint)) }
                    } else null,
                    enabled = !isBusy,
                    singleLine = true,
                    visualTransformation = secretTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = secretToggle,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.explorer_sftp_form_private_key_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = when {
                                state.isReadingKey -> stringResource(R.string.explorer_sftp_form_private_key_reading)
                                state.keyFileName != null -> state.keyFileName
                                keepsStored -> stringResource(R.string.explorer_sftp_form_private_key_saved)
                                else -> stringResource(R.string.explorer_sftp_form_private_key_none)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    OutlinedButton(
                        onClick = onPickKeyFile,
                        enabled = !isBusy,
                    ) {
                        Text(stringResource(R.string.explorer_sftp_form_private_key_choose_action))
                    }
                }

                OutlinedTextField(
                    value = fields.passphrase,
                    onValueChange = { fields.passphrase = it },
                    label = { Text(stringResource(R.string.explorer_sftp_form_passphrase_label)) },
                    supportingText = if (keepsStored && state.keyFileName == null && !isSignIn) {
                        { Text(stringResource(R.string.explorer_sftp_form_passphrase_kept_hint)) }
                    } else null,
                    enabled = !isBusy,
                    singleLine = true,
                    visualTransformation = secretTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = secretToggle,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.explorer_sftp_form_remember_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.explorer_network_form_remember_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = fields.rememberCredential,
                    onCheckedChange = { fields.rememberCredential = it },
                    enabled = !isBusy,
                )
            }

            if (!isPro) {
                Text(
                    text = stringResource(R.string.explorer_network_form_pro_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    onClick = { onSubmit(fields.toInput()) },
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
private fun SftpLocationFormSheetAddPreview() {
    SftpLocationFormSheet(
        state = ExplorerDialogState.SftpLocationForm(),
        onDismiss = {},
        onSubmit = {},
        onPickKeyFile = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpLocationFormSheetFreePreview() {
    SftpLocationFormSheet(
        state = ExplorerDialogState.SftpLocationForm(),
        onDismiss = {},
        onSubmit = {},
        onPickKeyFile = {},
        isPro = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpLocationFormSheetEditKeyPreview() {
    SftpLocationFormSheet(
        state = ExplorerDialogState.SftpLocationForm(
            mode = SftpFormMode.EDIT,
            existing = previewSftpLocation(),
            keyFileName = "id_ed25519",
        ),
        onDismiss = {},
        onSubmit = {},
        onPickKeyFile = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpLocationFormSheetSignInPreview() {
    SftpLocationFormSheet(
        state = ExplorerDialogState.SftpLocationForm(
            mode = SftpFormMode.SIGN_IN,
            existing = previewSftpLocation().copy(authType = SftpLocation.AuthType.PASSWORD),
        ),
        onDismiss = {},
        onSubmit = {},
        onPickKeyFile = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpLocationFormSheetTestingPreview() {
    SftpLocationFormSheet(
        state = ExplorerDialogState.SftpLocationForm(
            mode = SftpFormMode.EDIT,
            existing = previewSftpLocation(),
            isTesting = true,
        ),
        onDismiss = {},
        onSubmit = {},
        onPickKeyFile = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpLocationFormSheetErrorPreview() {
    SftpLocationFormSheet(
        state = ExplorerDialogState.SftpLocationForm(
            mode = SftpFormMode.EDIT,
            existing = previewSftpLocation(),
            error = "build.lan rejected the username, password or key".toCaString(),
        ),
        onDismiss = {},
        onSubmit = {},
        onPickKeyFile = {},
    )
}

internal fun previewSftpLocation() = SftpLocation(
    id = kotlin.uuid.Uuid.parse("66666666-7777-8888-9999-000000000000"),
    label = "Build server",
    host = "build.lan",
    username = "darken",
    basePath = "/srv/builds",
    authType = SftpLocation.AuthType.PRIVATE_KEY,
    rememberCredential = true,
    credentialVersion = 1,
    hostKey = previewHostKey(1),
    trustRevision = 1,
    createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
    updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
)

internal fun previewHostKey(seed: Int) = TrustedHostKey(
    type = "ssh-ed25519",
    blob = ByteArray(51) { seed.toByte() },
    fingerprint = when (seed) {
        1 -> "SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM"
        else -> "SHA256:m3r9Qk7fN2hYbWc8vXzL1aPpT6uE0sJdG4iKoRtVyHQ"
    },
)

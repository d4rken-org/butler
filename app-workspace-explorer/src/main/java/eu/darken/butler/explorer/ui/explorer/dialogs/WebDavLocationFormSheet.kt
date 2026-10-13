package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.NoEncryption
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
import eu.darken.butler.common.files.network.NetworkLocation
import eu.darken.butler.common.files.webdav.WebDavLocationInput
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import eu.darken.butler.common.R as CommonR

/**
 * Raw field contents, validated by [WebDavLocationInput] before anything is stored. [revision] counts
 * the edits made to the form, see [WebDavCertificateConfirmation].
 */
data class WebDavLocationFormInput(
    val label: String,
    val url: String,
    val authType: WebDavLocation.AuthType,
    val username: String,
    val password: String,
    val rememberCredential: Boolean,
    val revision: Int,
) {
    override fun toString(): String = "WebDavLocationFormInput($url, $authType, revision=$revision)"
}

/**
 * Whether a save may keep the stored password:
 * [eu.darken.butler.common.files.webdav.location.WebDavLocationManager.update] only allows that while
 * these stay the same.
 */
fun WebDavLocation.keepsCredentialFor(
    username: String,
    authType: WebDavLocation.AuthType,
    rememberCredential: Boolean,
): Boolean = authType == WebDavLocation.AuthType.BASIC &&
    authType == this.authType &&
    username == this.username &&
    rememberCredential == this.rememberCredential

/**
 * The form's fields, kept outside the sheet so a dialog stacked above it can read them. Every
 * change bumps [revision].
 */
@Stable
class WebDavLocationFormFields(existing: WebDavLocation?) {

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
    var url by Tracked(existing?.url.orEmpty())
    var authType by Tracked(existing?.authType ?: WebDavLocation.AuthType.BASIC)
    var username by Tracked(existing?.username.orEmpty())
    var password by Tracked("")

    // An anonymous location stores "not remembered", which says nothing about a password added later.
    var rememberCredential by Tracked(
        existing?.takeIf { it.authType == WebDavLocation.AuthType.BASIC }?.rememberCredential ?: true
    )

    fun toInput() = WebDavLocationFormInput(
        label = label,
        url = url,
        authType = authType,
        username = username,
        password = password,
        rememberCredential = rememberCredential,
        revision = revision,
    )
}

@Composable
fun rememberWebDavLocationFormFields(state: ExplorerDialogState.WebDavLocationForm): WebDavLocationFormFields =
    remember(state.formId) { WebDavLocationFormFields(state.existing) }

@Composable
fun WebDavLocationFormSheet(
    modifier: Modifier = Modifier,
    state: ExplorerDialogState.WebDavLocationForm,
    fields: WebDavLocationFormFields = rememberWebDavLocationFormFields(state),
    onDismiss: () -> Unit,
    onSubmit: (WebDavLocationFormInput) -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    isPro: Boolean = rememberIsPro(),
) {
    val context = LocalContext.current
    val existing = state.existing
    val isBusy = state.isTesting

    val usesPassword = fields.authType == WebDavLocation.AuthType.BASIC
    val keepsStored = existing?.keepsCredentialFor(
        username = fields.username.trim(),
        authType = fields.authType,
        rememberCredential = fields.rememberCredential,
    ) == true
    // A sign-in prompt exists because the stored password failed, so it always needs a fresh one.
    val isSignIn = state.mode == WebDavFormMode.SIGN_IN
    val hasCredential = !usesPassword ||
        (fields.username.isNotBlank() && (fields.password.isNotEmpty() || (keepsStored && !isSignIn)))
    val canSubmit = !isBusy &&
        state.certificateConfirmation == null &&
        state.retrustConfirmation == null &&
        fields.url.isNotBlank() &&
        hasCredential

    // What the last submit found, until the fields it was found in are edited.
    val issues = state.issues.takeIf { state.issuesRevision == fields.revision }.orEmpty()
    val urlIssue = issues.firstOrNull { it.field == WebDavLocationInput.Field.URL }
    val usernameIssue = issues.firstOrNull { it.field == WebDavLocationInput.Field.USERNAME }

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
                    WebDavFormMode.ADD -> stringResource(R.string.explorer_webdav_form_add_title)
                    WebDavFormMode.EDIT -> stringResource(R.string.explorer_webdav_form_edit_title)
                    WebDavFormMode.SIGN_IN -> stringResource(
                        R.string.explorer_webdav_form_sign_in_title,
                        existing?.displayName?.get(context).orEmpty(),
                    )
                },
                style = MaterialTheme.typography.headlineSmall,
            )

            if (isSignIn) {
                Text(
                    text = stringResource(
                        if (existing?.authType == WebDavLocation.AuthType.ANONYMOUS) {
                            R.string.explorer_webdav_form_sign_in_anonymous_hint
                        } else {
                            R.string.explorer_webdav_form_sign_in_password_hint
                        }
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
                value = fields.url,
                onValueChange = { fields.url = it },
                label = { Text(stringResource(R.string.explorer_webdav_form_url_label)) },
                placeholder = { Text(stringResource(R.string.explorer_webdav_form_url_hint)) },
                supportingText = urlIssue?.let { { Text(stringResource(it.messageRes())) } },
                isError = urlIssue != null,
                enabled = !isBusy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            if (WebDavLocationInput.isCleartext(fields.url)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.NoEncryption,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.explorer_webdav_form_cleartext_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !usesPassword,
                    onClick = { fields.authType = WebDavLocation.AuthType.ANONYMOUS },
                    label = { Text(stringResource(R.string.explorer_webdav_form_auth_anonymous)) },
                    enabled = !isBusy,
                )
                FilterChip(
                    selected = usesPassword,
                    onClick = { fields.authType = WebDavLocation.AuthType.BASIC },
                    label = { Text(stringResource(R.string.explorer_network_form_auth_password)) },
                    enabled = !isBusy,
                )
            }

            if (usesPassword) {
                OutlinedTextField(
                    value = fields.username,
                    onValueChange = { fields.username = it },
                    label = { Text(stringResource(R.string.explorer_network_form_username_label)) },
                    supportingText = usernameIssue?.let { { Text(stringResource(it.messageRes())) } },
                    isError = usernameIssue != null,
                    enabled = !isBusy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = fields.password,
                    onValueChange = { fields.password = it },
                    label = { Text(stringResource(R.string.explorer_network_form_password_label)) },
                    supportingText = if (keepsStored && !isSignIn) {
                        { Text(stringResource(R.string.explorer_network_form_password_kept_hint)) }
                    } else null,
                    enabled = !isBusy,
                    singleLine = true,
                    visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { secretVisible = !secretVisible }) {
                            Icon(
                                imageVector = if (secretVisible) Icons.TwoTone.VisibilityOff else Icons.TwoTone.Visibility,
                                contentDescription = stringResource(
                                    if (secretVisible) R.string.explorer_info_network_password_hide_action
                                    else R.string.explorer_info_network_password_show_action
                                ),
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
                        checked = fields.rememberCredential,
                        onCheckedChange = { fields.rememberCredential = it },
                        enabled = !isBusy,
                    )
                }
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

internal fun WebDavLocationInput.Issue.messageRes(): Int = when (this) {
    WebDavLocationInput.Issue.UrlBlank -> R.string.explorer_webdav_form_error_url_blank
    WebDavLocationInput.Issue.UrlSchemeUnsupported -> R.string.explorer_webdav_form_error_url_scheme
    WebDavLocationInput.Issue.UrlHasUserInfo -> R.string.explorer_webdav_form_error_url_user_info
    WebDavLocationInput.Issue.UrlHasQuery -> R.string.explorer_webdav_form_error_url_query
    WebDavLocationInput.Issue.UrlHasFragment -> R.string.explorer_webdav_form_error_url_fragment
    WebDavLocationInput.Issue.HostMalformed -> R.string.explorer_network_form_error_host_malformed
    WebDavLocationInput.Issue.PortOutOfRange -> R.string.explorer_network_form_error_port
    WebDavLocationInput.Issue.UsernameBlank -> R.string.explorer_network_form_error_username_blank
    WebDavLocationInput.Issue.UsernameMalformed -> R.string.explorer_webdav_form_error_username_malformed
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavLocationFormSheetAddPreview() {
    WebDavLocationFormSheet(
        state = ExplorerDialogState.WebDavLocationForm(),
        onDismiss = {},
        onSubmit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavLocationFormSheetFreePreview() {
    WebDavLocationFormSheet(
        state = ExplorerDialogState.WebDavLocationForm(),
        onDismiss = {},
        onSubmit = {},
        isPro = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavLocationFormSheetCleartextPreview() {
    WebDavLocationFormSheet(
        state = ExplorerDialogState.WebDavLocationForm(
            mode = WebDavFormMode.EDIT,
            existing = previewWebDavLocation().copy(url = "http://nas.local:8080/dav/"),
        ),
        onDismiss = {},
        onSubmit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavLocationFormSheetSignInPreview() {
    WebDavLocationFormSheet(
        state = ExplorerDialogState.WebDavLocationForm(
            mode = WebDavFormMode.SIGN_IN,
            existing = previewWebDavLocation(),
        ),
        onDismiss = {},
        onSubmit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavLocationFormSheetTestingPreview() {
    WebDavLocationFormSheet(
        state = ExplorerDialogState.WebDavLocationForm(
            mode = WebDavFormMode.EDIT,
            existing = previewWebDavLocation(),
            isTesting = true,
        ),
        onDismiss = {},
        onSubmit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavLocationFormSheetErrorPreview() {
    WebDavLocationFormSheet(
        state = ExplorerDialogState.WebDavLocationForm(
            mode = WebDavFormMode.EDIT,
            existing = previewWebDavLocation(),
            error = "cloud.example.com rejected the username or password".toCaString(),
        ),
        onDismiss = {},
        onSubmit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavLocationFormSheetFieldIssuesPreview() {
    val fields = remember {
        WebDavLocationFormFields(null).apply {
            url = "https://alice@cloud.example.com/remote.php/dav/"
            username = "alice:smith"
        }
    }
    WebDavLocationFormSheet(
        state = ExplorerDialogState.WebDavLocationForm(
            issues = listOf(WebDavLocationInput.Issue.UrlHasUserInfo, WebDavLocationInput.Issue.UsernameMalformed),
            issuesRevision = fields.revision,
        ),
        fields = fields,
        onDismiss = {},
        onSubmit = {},
    )
}

internal fun previewWebDavLocation(): WebDavLocation =
    (MockDataProvider.createMockStorageWebDav().location as NetworkLocation.WebDav).location

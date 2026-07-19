package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R

private const val WEAK_PASSWORD_THRESHOLD = 8

/**
 * Password + confirm-password pair for archive encryption. The confirm field only appears once a
 * password is entered; an empty password means "no encryption".
 */
@Composable
fun ArchivePasswordFields(
    modifier: Modifier = Modifier,
    password: String,
    confirmPassword: String,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    val mismatch = confirmPassword.isNotEmpty() && password != confirmPassword
    val weak = password.isNotEmpty() && password.length < WEAK_PASSWORD_THRESHOLD

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.explorer_compress_dialog_password_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            supportingText = when {
                weak -> {
                    { Text(stringResource(R.string.explorer_compress_dialog_password_weak)) }
                }
                password.isNotEmpty() -> {
                    { Text(stringResource(R.string.explorer_compress_dialog_password_notice)) }
                }
                else -> null
            },
            trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.TwoTone.VisibilityOff else Icons.TwoTone.Visibility,
                        contentDescription = stringResource(
                            if (revealed) {
                                R.string.explorer_compress_dialog_password_hide
                            } else {
                                R.string.explorer_compress_dialog_password_show
                            }
                        ),
                    )
                }
            },
        )
        if (password.isNotEmpty()) {
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = onConfirmPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.explorer_compress_dialog_password_confirm_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                isError = mismatch,
                supportingText = if (mismatch) {
                    { Text(stringResource(R.string.explorer_compress_dialog_password_mismatch)) }
                } else {
                    null
                },
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchivePasswordFieldsEmptyPreview() {
    PreviewWrapper {
        ArchivePasswordFields(
            password = "",
            confirmPassword = "",
            onPasswordChange = {},
            onConfirmPasswordChange = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchivePasswordFieldsMismatchPreview() {
    PreviewWrapper {
        ArchivePasswordFields(
            password = "hunter2",
            confirmPassword = "hunter",
            onPasswordChange = {},
            onConfirmPasswordChange = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchivePasswordFieldsWeakPreview() {
    PreviewWrapper {
        ArchivePasswordFields(
            password = "abc",
            confirmPassword = "",
            onPasswordChange = {},
            onConfirmPasswordChange = {},
        )
    }
}

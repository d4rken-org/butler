package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.CompressionPreset
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.common.R as CommonR

/** Formats offered for creating a new archive. */
private val COMPRESS_FORMATS = listOf(ArchiveFormat.ZIP, ArchiveFormat.TAR_GZ)

@Composable
fun CompressOptionsDialog(
    suggestedName: String,
    defaultFormat: ArchiveFormat = ArchiveFormat.ZIP,
    defaultPreset: CompressionPreset = CompressionPreset.NORMAL,
    onValidate: (String) -> FilenameValidator.ValidationResult = { FilenameValidator.ValidationResult.Valid },
    onDismiss: () -> Unit,
    onConfirm: (name: String, format: ArchiveFormat, preset: CompressionPreset, password: String?) -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var format by remember {
        mutableStateOf(defaultFormat.takeIf { it in COMPRESS_FORMATS } ?: ArchiveFormat.ZIP)
    }
    var preset by remember { mutableStateOf(defaultPreset) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    val validation = remember(name) { onValidate(name) }
    val nameInvalid = validation is FilenameValidator.ValidationResult.Invalid
    val passwordsMatch = password.isEmpty() || password == confirmPassword

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        includeImePadding = true,
        title = { Text(stringResource(R.string.explorer_compress_dialog_title)) },
        text = {
            // No verticalScroll here: PaneBoundAlertDialog already scrolls its title/text block, and
            // a nested scroller would be measured with an infinite height constraint and crash.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.explorer_compress_dialog_name_label)) },
                    isError = nameInvalid,
                    supportingText = if (nameInvalid) {
                        {
                            Text(validation.invalidChars.joinToString(" "))
                        }
                    } else {
                        null
                    },
                )
                Text(
                    text = stringResource(R.string.explorer_compress_dialog_format_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    COMPRESS_FORMATS.forEach { option ->
                        SelectableOptionRow(
                            selected = format == option,
                            label = option.displayExtension,
                            onClick = { format = option },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.explorer_compress_dialog_level_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    CompressionPreset.entries.forEach { option ->
                        SelectableOptionRow(
                            selected = preset == option,
                            label = stringResource(option.label),
                            onClick = { preset = option },
                        )
                    }
                }
                if (format == ArchiveFormat.ZIP) {
                    ArchivePasswordFields(
                        password = password,
                        confirmPassword = confirmPassword,
                        onPasswordChange = { password = it },
                        onConfirmPasswordChange = { confirmPassword = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name.trim(),
                        format,
                        preset,
                        password.takeIf { format == ArchiveFormat.ZIP && it.isNotBlank() },
                    )
                },
                enabled = name.isNotBlank() && !nameInvalid && passwordsMatch,
            ) {
                Text(stringResource(R.string.explorer_compress_dialog_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Composable
private fun SelectableOptionRow(
    modifier: Modifier = Modifier,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label)
    }
}

private val CompressionPreset.label: Int
    get() = when (this) {
        CompressionPreset.FAST -> R.string.explorer_compress_dialog_level_fast
        CompressionPreset.NORMAL -> R.string.explorer_compress_dialog_level_normal
        CompressionPreset.BEST -> R.string.explorer_compress_dialog_level_best
    }

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CompressOptionsDialogPreview() {
    CompressOptionsDialog(
        suggestedName = "Documents",
        onDismiss = {},
        onConfirm = { _, _, _, _ -> },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CompressOptionsDialogTarGzPreview() {
    CompressOptionsDialog(
        suggestedName = "backup",
        defaultFormat = ArchiveFormat.TAR_GZ,
        defaultPreset = CompressionPreset.BEST,
        onDismiss = {},
        onConfirm = { _, _, _, _ -> },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CompressOptionsDialogInvalidNamePreview() {
    CompressOptionsDialog(
        suggestedName = "bad/name",
        onValidate = { FilenameValidator.ValidationResult.Invalid(setOf('/'), FilenameValidator.StorageContext.PUBLIC) },
        onDismiss = {},
        onConfirm = { _, _, _, _ -> },
    )
}

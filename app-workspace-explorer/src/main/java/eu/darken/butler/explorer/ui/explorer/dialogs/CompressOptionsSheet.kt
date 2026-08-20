package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.CompressionPreset
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.common.R as CommonR

/**
 * Formats offered for creating a new archive, each paired with the trade it makes.
 *
 * The caption is bound here rather than in a `when` over [ArchiveFormat]: that enum carries entries
 * this sheet does not offer, so a fallback branch would hand a newly offered format someone else's
 * caption, claiming password support for a format that has none.
 */
private enum class CompressFormat(
    val format: ArchiveFormat,
    val hint: Int,
    /**
     * Whether this format takes a password, which decides whether the password fields are shown.
     * A constructor property rather than `this == ZIP` so a new encryptable entry has to state it
     * instead of compiling as `false` and silently losing its password fields.
     * `ArchiveWriteOptions` enforces the same rule at the domain boundary.
     */
    val encryptable: Boolean,
) {
    ZIP(ArchiveFormat.ZIP, R.string.explorer_compress_dialog_format_zip_hint, encryptable = true),
    TAR_GZ(ArchiveFormat.TAR_GZ, R.string.explorer_compress_dialog_format_targz_hint, encryptable = false),
    ;

    companion object {
        /** Falls back to [ZIP] for a remembered default this sheet does not offer. */
        fun of(format: ArchiveFormat): CompressFormat = entries.firstOrNull { it.format == format } ?: ZIP
    }
}

/** Vertical gap between the sheet's labelled sections. */
private val SECTION_SPACING = 20.dp

/** Vertical gap between a section's own label, control and caption. */
private val WITHIN_SECTION_SPACING = 8.dp

@Composable
fun CompressOptionsSheet(
    modifier: Modifier = Modifier,
    suggestedName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, format: ArchiveFormat, preset: CompressionPreset, password: String?) -> Unit,
    defaultFormat: ArchiveFormat = ArchiveFormat.ZIP,
    defaultPreset: CompressionPreset = CompressionPreset.NORMAL,
    onValidate: (String) -> FilenameValidator.ValidationResult = { FilenameValidator.ValidationResult.Valid },
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        modifier = modifier,
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        // Name and password fields are editable, so the content has to stay above the keyboard.
        includeImePadding = true,
    ) {
        CompressOptionsContent(
            suggestedName = suggestedName,
            defaultFormat = defaultFormat,
            defaultPreset = defaultPreset,
            onValidate = onValidate,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

@Composable
private fun CompressOptionsContent(
    suggestedName: String,
    defaultFormat: ArchiveFormat,
    defaultPreset: CompressionPreset,
    onValidate: (String) -> FilenameValidator.ValidationResult,
    onDismiss: () -> Unit,
    onConfirm: (name: String, format: ArchiveFormat, preset: CompressionPreset, password: String?) -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var format by remember { mutableStateOf(CompressFormat.of(defaultFormat)) }
    var preset by remember { mutableStateOf(defaultPreset) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    val validation = remember(name) { onValidate(name) }
    val nameInvalid = validation is FilenameValidator.ValidationResult.Invalid
    // Gated on the format: a half-typed password left behind by a switch to tar.gz would otherwise
    // keep Create disabled with no visible field to explain why. The text is kept rather than
    // cleared so switching back to zip does not silently discard what was typed.
    val passwordsMatch = !format.encryptable || password.isEmpty() || password == confirmPassword

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        // Only between sections. Each section below groups its own label with its own control, so a
        // label always sits closer to what it labels than to the section above it.
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
        Text(
            text = stringResource(R.string.explorer_compress_dialog_title),
            style = MaterialTheme.typography.titleLarge,
        )

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

        Section(label = stringResource(R.string.explorer_compress_dialog_format_label)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CompressFormat.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = format == option,
                        onClick = { format = option },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = CompressFormat.entries.size,
                        ),
                    ) {
                        Text(option.format.displayExtension)
                    }
                }
            }
            // States the trade the choice actually makes, and pre-explains why the password fields
            // below disappear for tar.gz instead of letting them silently blink out.
            Text(
                text = stringResource(format.hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(label = stringResource(R.string.explorer_compress_dialog_level_label)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CompressionPreset.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = preset == option,
                        onClick = { preset = option },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = CompressionPreset.entries.size,
                        ),
                    ) {
                        Text(stringResource(option.label))
                    }
                }
            }
        }

        if (format.encryptable) {
            ArchivePasswordFields(
                password = password,
                confirmPassword = confirmPassword,
                onPasswordChange = { password = it },
                onConfirmPasswordChange = { confirmPassword = it },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
            Button(
                onClick = {
                    onConfirm(
                        name.trim(),
                        format.format,
                        preset,
                        password.takeIf { format.encryptable && it.isNotBlank() },
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank() && !nameInvalid && passwordsMatch,
            ) {
                Text(stringResource(R.string.explorer_compress_dialog_create_action))
            }
        }
    }
}

/** A section label and the control it belongs to, held closer to each other than to their neighbours. */
@Composable
private fun Section(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WITHIN_SECTION_SPACING),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
        )
        content()
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
private fun CompressOptionsSheetPreview() {
    CompressOptionsSheet(
        suggestedName = "Documents",
        onDismiss = {},
        onConfirm = { _, _, _, _ -> },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CompressOptionsSheetTarGzPreview() {
    CompressOptionsSheet(
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
private fun CompressOptionsSheetInvalidNamePreview() {
    CompressOptionsSheet(
        suggestedName = "bad/name",
        onValidate = { FilenameValidator.ValidationResult.Invalid(setOf('/'), FilenameValidator.StorageContext.PUBLIC) },
        onDismiss = {},
        onConfirm = { _, _, _, _ -> },
    )
}

package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.text.TextCharsets
import eu.darken.butler.viewer.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import java.nio.charset.Charset
import eu.darken.butler.common.R as CommonR

/**
 * Picks the encoding the text preview reads with. Null stands for "Automatic", which names
 * [autoCharset] when detection has read the file before.
 */
@Composable
fun TextEncodingDialog(
    modifier: Modifier = Modifier,
    selected: Charset?,
    autoCharset: Charset?,
    charsets: List<Charset> = TextCharsets.selectable,
    onSelect: (Charset?) -> Unit,
    onDismiss: () -> Unit,
) {
    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.viewer_text_encoding_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                EncodingOption(
                    label = if (autoCharset != null) {
                        stringResource(R.string.viewer_text_encoding_automatic_detected, autoCharset.name())
                    } else {
                        stringResource(R.string.viewer_text_encoding_automatic)
                    },
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
                charsets.forEach { charset ->
                    EncodingOption(
                        label = charset.name(),
                        selected = charset == selected,
                        onClick = { onSelect(charset) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Composable
private fun EncodingOption(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextEncodingDialogPreview() {
    TextEncodingDialog(
        selected = null,
        autoCharset = TextCharsets.GB18030,
        onSelect = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextEncodingDialogPickedPreview() {
    TextEncodingDialog(
        selected = Charsets.UTF_16LE,
        autoCharset = null,
        onSelect = {},
        onDismiss = {},
    )
}

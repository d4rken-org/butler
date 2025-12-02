package eu.darken.butler.common.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

private const val MB_MULTIPLIER = 1024L * 1024L

/**
 * A dialog for inputting file size values with a slider and text field.
 * The slider and text field are bidirectionally synced.
 *
 * @param title The dialog title
 * @param currentSize The current size in bytes
 * @param minimumSize The minimum allowed size in bytes
 * @param maximumSize The maximum allowed size in bytes
 * @param defaultSize The default size in bytes for the reset button
 * @param onDismiss Called when the dialog is dismissed
 * @param onConfirm Called when the user confirms with the selected size in bytes
 */
@Composable
fun SizeInputDialog(
    modifier: Modifier = Modifier,
    title: String,
    currentSize: Long,
    minimumSize: Long = 1L * MB_MULTIPLIER,
    maximumSize: Long = 10L * 1024L * MB_MULTIPLIER,
    defaultSize: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val context = LocalContext.current
    val parser = remember { SizeParser(context) }

    val minMB = (minimumSize / MB_MULTIPLIER).toFloat()
    val maxMB = (maximumSize / MB_MULTIPLIER).toFloat()

    var sliderValue by remember { mutableFloatStateOf((currentSize / MB_MULTIPLIER).toFloat()) }
    var textValue by remember { mutableStateOf(Formatter.formatShortFileSize(context, currentSize)) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun getSliderBytes(): Long = sliderValue.toLong() * MB_MULTIPLIER

    fun updateFromSlider(newValue: Float) {
        sliderValue = newValue
        textValue = Formatter.formatShortFileSize(context, getSliderBytes())
        isError = false
        errorMessage = null
    }

    fun updateFromText(newText: String) {
        textValue = newText
        val parsed = parser.parse(newText)
        when {
            parsed != null && parsed in minimumSize..maximumSize -> {
                sliderValue = (parsed / MB_MULTIPLIER).toFloat()
                isError = false
                errorMessage = null
            }
            parsed != null -> {
                isError = true
                val minFormatted = Formatter.formatShortFileSize(context, minimumSize)
                val maxFormatted = Formatter.formatShortFileSize(context, maximumSize)
                errorMessage = "$minFormatted ≤ X ≤ $maxFormatted"
            }
            else -> {
                isError = true
                errorMessage = context.getString(R.string.general_error_invalid_input_label)
            }
        }
    }

    fun resetToDefault() {
        sliderValue = (defaultSize / MB_MULTIPLIER).toFloat()
        textValue = Formatter.formatShortFileSize(context, defaultSize)
        isError = false
        errorMessage = null
    }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { updateFromText(it) },
                    label = { Text(stringResource(R.string.general_size_input_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = isError,
                    supportingText = errorMessage?.let { msg -> { Text(msg) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!isError) {
                                onConfirm(getSliderBytes())
                            }
                        }
                    ),
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { updateFromSlider(it) },
                    valueRange = minMB..maxMB,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(getSliderBytes()) },
                enabled = !isError,
            ) {
                Text(stringResource(R.string.general_save_action))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { resetToDefault() }) {
                    Text(stringResource(R.string.general_reset_action))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.general_cancel_action))
                }
            }
        },
    )
}

@Preview2
@Composable
private fun SizeInputDialogPreview() {
    PreviewWrapper {
        SizeInputDialog(
            title = "Maximum size per storage",
            currentSize = 500L * MB_MULTIPLIER,
            minimumSize = 1L * MB_MULTIPLIER,
            maximumSize = 10L * 1024L * MB_MULTIPLIER,
            defaultSize = 500L * MB_MULTIPLIER,
            onDismiss = {},
            onConfirm = {},
        )
    }
}

package eu.darken.butler.searcher.ui.settings

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
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R

@Composable
fun MaxHistoryItemsDialog(
    modifier: Modifier = Modifier,
    currentValue: Int,
    minValue: Int = 1,
    maxValue: Int = 500,
    defaultValue: Int = 50,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val context = LocalContext.current

    var sliderValue by remember { mutableFloatStateOf(currentValue.toFloat()) }
    var textValue by remember { mutableStateOf(currentValue.toString()) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun updateFromSlider(newValue: Float) {
        sliderValue = newValue
        textValue = newValue.toInt().toString()
        isError = false
        errorMessage = null
    }

    fun updateFromText(newText: String) {
        textValue = newText
        val parsed = newText.trim().toIntOrNull()
        when {
            parsed != null && parsed in minValue..maxValue -> {
                sliderValue = parsed.toFloat()
                isError = false
                errorMessage = null
            }
            parsed != null -> {
                isError = true
                errorMessage = "$minValue ≤ X ≤ $maxValue"
            }
            else -> {
                isError = true
                errorMessage = context.getString(eu.darken.butler.common.R.string.general_error_invalid_input_label)
            }
        }
    }

    fun resetToDefault() {
        sliderValue = defaultValue.toFloat()
        textValue = defaultValue.toString()
        isError = false
        errorMessage = null
    }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.searcher_settings_max_history_label),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { updateFromText(it) },
                    label = { Text(stringResource(R.string.searcher_settings_max_history_input_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = isError,
                    supportingText = errorMessage?.let { msg -> { Text(msg) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!isError) {
                                onConfirm(sliderValue.toInt())
                            }
                        }
                    ),
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { updateFromSlider(it) },
                    valueRange = minValue.toFloat()..maxValue.toFloat(),
                    steps = (maxValue - minValue) / 10 - 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(sliderValue.toInt()) },
                enabled = !isError,
            ) {
                Text(stringResource(eu.darken.butler.common.R.string.general_save_action))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { resetToDefault() }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_reset_action))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            }
        },
    )
}

@Preview2
@Composable
private fun MaxHistoryItemsDialogPreview() {
    PreviewWrapper {
        MaxHistoryItemsDialog(
            currentValue = 50,
            onDismiss = {},
            onConfirm = {},
        )
    }
}

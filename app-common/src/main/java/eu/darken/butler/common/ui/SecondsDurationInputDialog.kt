package eu.darken.butler.common.ui

import android.content.Context
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A dialog for inputting duration values in seconds with a slider and text field.
 * The slider and text field are bidirectionally synced.
 *
 * @param title The dialog title
 * @param currentDuration The current duration value
 * @param minimumDuration The minimum allowed duration
 * @param maximumDuration The maximum allowed duration
 * @param defaultDuration The default duration for the reset button
 * @param onDismiss Called when the dialog is dismissed
 * @param onConfirm Called when the user confirms with the selected duration
 */
@Composable
fun SecondsDurationInputDialog(
    modifier: Modifier = Modifier,
    title: String,
    currentDuration: Duration,
    minimumDuration: Duration = 10.seconds,
    maximumDuration: Duration = 300.seconds,
    defaultDuration: Duration,
    onDismiss: () -> Unit,
    onConfirm: (Duration) -> Unit,
) {
    val context = LocalContext.current

    var sliderValue by remember { mutableFloatStateOf(currentDuration.inWholeSeconds.toFloat()) }
    var textValue by remember { mutableStateOf(formatSeconds(context, currentDuration)) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val minSeconds = minimumDuration.inWholeSeconds.toFloat()
    val maxSeconds = maximumDuration.inWholeSeconds.toFloat()

    fun updateFromSlider(newValue: Float) {
        sliderValue = newValue
        textValue = formatSeconds(context, newValue.toLong().seconds)
        isError = false
        errorMessage = null
    }

    fun updateFromText(newText: String) {
        textValue = newText
        val parsed = parseSeconds(newText)
        when {
            parsed != null && parsed in minimumDuration..maximumDuration -> {
                sliderValue = parsed.inWholeSeconds.toFloat()
                isError = false
                errorMessage = null
            }
            parsed != null -> {
                isError = true
                val minFormatted = formatSeconds(context, minimumDuration)
                val maxFormatted = formatSeconds(context, maximumDuration)
                errorMessage = "$minFormatted ≤ X ≤ $maxFormatted"
            }
            else -> {
                isError = true
                errorMessage = context.getString(R.string.general_error_invalid_input_label)
            }
        }
    }

    fun resetToDefault() {
        sliderValue = defaultDuration.inWholeSeconds.toFloat()
        textValue = formatSeconds(context, defaultDuration)
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
                    label = { Text(stringResource(R.string.general_duration_input_label)) },
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
                                onConfirm(sliderValue.toLong().seconds)
                            }
                        }
                    ),
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { updateFromSlider(it) },
                    valueRange = minSeconds..maxSeconds,
                    steps = (maxSeconds - minSeconds - 1).toInt().coerceAtLeast(0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(sliderValue.toLong().seconds) },
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

private fun formatSeconds(context: Context, duration: Duration): String {
    val seconds = duration.inWholeSeconds.toInt()
    return context.resources.getQuantityString(
        R.plurals.common_duration_seconds_full,
        seconds,
        seconds,
    )
}

private fun parseSeconds(input: String): Duration? {
    val trimmed = input.trim()
    // Try to extract the number from strings like "30 seconds" or just "30"
    val numericPart = trimmed.replace(Regex("[^0-9]"), "")
    val value = numericPart.toLongOrNull() ?: return null
    return value.seconds
}

@Preview2
@Composable
private fun SecondsDurationInputDialogPreview() {
    PreviewWrapper {
        SecondsDurationInputDialog(
            title = "Auto-save interval",
            currentDuration = 30.seconds,
            minimumDuration = 10.seconds,
            maximumDuration = 300.seconds,
            defaultDuration = 30.seconds,
            onDismiss = {},
            onConfirm = {},
        )
    }
}

package eu.darken.butler.common.ui

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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * A dialog for inputting duration values with a slider and text field.
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
fun DurationInputDialog(
    modifier: Modifier = Modifier,
    title: String,
    currentDuration: Duration,
    minimumDuration: Duration = 1.days,
    maximumDuration: Duration = 365.days,
    defaultDuration: Duration,
    onDismiss: () -> Unit,
    onConfirm: (Duration) -> Unit,
) {
    val context = LocalContext.current
    val parser = remember { DurationParser(context) }

    var sliderValue by remember { mutableFloatStateOf(currentDuration.inWholeDays.toFloat()) }
    var textValue by remember { mutableStateOf(formatDuration(context, currentDuration)) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val minDays = minimumDuration.inWholeDays.toFloat()
    val maxDays = maximumDuration.inWholeDays.toFloat()

    fun updateFromSlider(newValue: Float) {
        sliderValue = newValue
        textValue = formatDuration(context, newValue.toLong().days)
        isError = false
        errorMessage = null
    }

    fun updateFromText(newText: String) {
        textValue = newText
        val parsed = parser.parse(newText)
        when {
            parsed != null && parsed in minimumDuration..maximumDuration -> {
                sliderValue = parsed.inWholeDays.toFloat()
                isError = false
                errorMessage = null
            }
            parsed != null -> {
                isError = true
                val minFormatted = formatDuration(context, minimumDuration)
                val maxFormatted = formatDuration(context, maximumDuration)
                errorMessage = "$minFormatted ≤ X ≤ $maxFormatted"
            }
            else -> {
                isError = true
                errorMessage = context.getString(R.string.general_error_invalid_input_label)
            }
        }
    }

    fun resetToDefault() {
        sliderValue = defaultDuration.inWholeDays.toFloat()
        textValue = formatDuration(context, defaultDuration)
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
                                onConfirm(sliderValue.toLong().days)
                            }
                        }
                    ),
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { updateFromSlider(it) },
                    valueRange = minDays..maxDays,
                    steps = (maxDays - minDays - 1).toInt().coerceAtLeast(0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(sliderValue.toLong().days) },
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

private fun formatDuration(context: android.content.Context, duration: Duration): String {
    val days = duration.inWholeDays.toInt()
    return context.resources.getQuantityString(
        R.plurals.common_duration_days_full,
        days,
        days,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DurationInputDialogPreview() {
    DurationInputDialog(
        title = "Auto-delete period",
        currentDuration = 30.days,
        minimumDuration = 1.days,
        maximumDuration = 365.days,
        defaultDuration = 30.days,
        onDismiss = {},
        onConfirm = {},
    )
}

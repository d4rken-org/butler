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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val STEP_MINUTES = 15

/**
 * A dialog for inputting coarse duration values with a slider and text field, snapped to
 * [STEP_MINUTES] intervals. The slider and text field are bidirectionally synced.
 *
 * Sibling of [SecondsDurationInputDialog] for durations that span hours: the slider steps in
 * quarter hours, free text is snapped to the nearest step instead of rejected, and values outside
 * the range are clamped.
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
fun MinutesDurationInputDialog(
    modifier: Modifier = Modifier,
    title: String,
    currentDuration: Duration,
    minimumDuration: Duration = 15.minutes,
    maximumDuration: Duration = 12.hours,
    defaultDuration: Duration,
    onDismiss: () -> Unit,
    onConfirm: (Duration) -> Unit,
) {
    val context = LocalContext.current

    fun snap(duration: Duration): Duration {
        val clamped = duration.coerceIn(minimumDuration, maximumDuration)
        val steps = (clamped.inWholeMinutes.toDouble() / STEP_MINUTES).roundToLong()
        return (steps * STEP_MINUTES).minutes.coerceIn(minimumDuration, maximumDuration)
    }

    var sliderValue by remember { mutableFloatStateOf(snap(currentDuration).inWholeMinutes.toFloat()) }
    var textValue by remember { mutableStateOf(formatMinutes(context, snap(currentDuration))) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val minMinutes = minimumDuration.inWholeMinutes.toFloat()
    val maxMinutes = maximumDuration.inWholeMinutes.toFloat()

    fun apply(duration: Duration) {
        val snapped = snap(duration)
        sliderValue = snapped.inWholeMinutes.toFloat()
        textValue = formatMinutes(context, snapped)
        isError = false
        errorMessage = null
    }

    fun updateFromText(newText: String) {
        textValue = newText
        val parsed = parseMinutes(newText)
        if (parsed == null) {
            isError = true
            errorMessage = context.getString(R.string.general_error_invalid_input_label)
            return
        }
        sliderValue = snap(parsed).inWholeMinutes.toFloat()
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
                                onConfirm(sliderValue.toLong().minutes)
                            }
                        }
                    ),
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { apply(it.toLong().minutes) },
                    valueRange = minMinutes..maxMinutes,
                    steps = (((maxMinutes - minMinutes) / STEP_MINUTES).toInt() - 1).coerceAtLeast(0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(sliderValue.toLong().minutes) },
                enabled = !isError,
            ) {
                Text(stringResource(R.string.general_save_action))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { apply(defaultDuration) }) {
                    Text(stringResource(R.string.general_reset_action))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.general_cancel_action))
                }
            }
        },
    )
}

private fun formatMinutes(context: Context, duration: Duration): String {
    val hours = duration.inWholeHours.toInt()
    val minutes = (duration.inWholeMinutes - hours * 60L).toInt()
    val hoursText = context.resources.getQuantityString(R.plurals.common_duration_hours_full, hours, hours)
    val minutesText = context.resources.getQuantityString(R.plurals.common_duration_minutes_full, minutes, minutes)
    return when {
        hours > 0 && minutes > 0 -> "$hoursText $minutesText"
        hours > 0 -> hoursText
        else -> minutesText
    }
}

/**
 * Parses "2 hours 30 minutes", "90 minutes", "2h 30m" or a bare "90" (minutes). Returns null when
 * no number is present at all.
 */
private fun parseMinutes(input: String): Duration? {
    val normalized = input.trim().lowercase()
    if (normalized.isEmpty()) return null

    var total = Duration.ZERO
    var matched = false
    Regex("(\\d+)\\s*([a-z]*)").findAll(normalized).forEach { match ->
        val amount = match.groupValues[1].toLongOrNull() ?: return@forEach
        val unit = match.groupValues[2]
        matched = true
        total += when {
            unit.startsWith("h") -> amount.hours
            else -> amount.minutes
        }
    }
    return if (matched) total else null
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MinutesDurationInputDialogPreview() {
    MinutesDurationInputDialog(
        title = "Pause after",
        currentDuration = 2.hours,
        defaultDuration = 2.hours,
        onDismiss = {},
        onConfirm = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MinutesDurationInputDialogMinutesPreview() {
    MinutesDurationInputDialog(
        title = "Pause after",
        currentDuration = 45.minutes,
        defaultDuration = 2.hours,
        onDismiss = {},
        onConfirm = {},
    )
}

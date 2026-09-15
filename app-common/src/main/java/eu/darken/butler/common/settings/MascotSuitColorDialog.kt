package eu.darken.butler.common.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.LocalMascotSuitColor
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.mascotDefaultSuitColor
import java.util.Locale
import android.graphics.Color as AndroidColor

/**
 * Picks the color Butler's outfit is tailored from. The rest of the outfit is derived from it, so
 * the dialog shows him wearing the in-progress color rather than a swatch of it.
 */
@Composable
fun MascotSuitColorDialog(
    suitColor: Int?,
    onColorSelected: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val fallback = mascotDefaultSuitColor()
    val initial = remember(suitColor, fallback) { (suitColor ?: fallback.toArgb()).toHsv() }
    var picked by remember(initial) { mutableStateOf(initial) }

    MascotSuitColorDialogContent(
        picked = picked,
        onPickedChange = { picked = it },
        onConfirm = {
            // An untouched picker shows the current theme's default, not a choice. Storing that
            // would pin this theme's suit onto the other one too, as the override spans both.
            val chosen = if (suitColor == null && picked == initial) null else picked.toRgb()
            onColorSelected(chosen)
        },
        onDefault = { onColorSelected(null) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun MascotSuitColorDialogContent(
    picked: Hsv,
    onPickedChange: (Hsv) -> Unit,
    onConfirm: () -> Unit,
    onDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val preview = Color.hsv(picked.hue, picked.saturation, picked.brightness)
    val hex = "#%06X".format(Locale.ROOT, preview.toArgb() and 0xFFFFFF)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.ui_mascot_suit_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(LocalMascotSuitColor provides preview) {
                    ButlerMascot(
                        modifier = Modifier.size(120.dp),
                        variant = ButlerMascotMode.Static.Normal(hat = ButlerMascotMode.Hat.NO_HAT),
                    )
                }

                Spacer(Modifier.height(16.dp))

                SaturationBrightnessField(
                    picked = picked,
                    onPickedChange = onPickedChange,
                )

                Spacer(Modifier.height(8.dp))

                ChannelSlider(
                    label = stringResource(R.string.ui_mascot_suit_hue_label),
                    value = picked.hue,
                    valueRange = 0f..360f,
                    onValueChange = { onPickedChange(picked.copy(hue = it)) },
                )
                ChannelSlider(
                    label = stringResource(R.string.ui_mascot_suit_saturation_label),
                    value = picked.saturation,
                    valueRange = 0f..1f,
                    onValueChange = { onPickedChange(picked.copy(saturation = it)) },
                )
                ChannelSlider(
                    label = stringResource(R.string.ui_mascot_suit_brightness_label),
                    value = picked.brightness,
                    valueRange = 0f..1f,
                    onValueChange = { onPickedChange(picked.copy(brightness = it)) },
                )

                Text(
                    text = hex,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = context.getString(R.string.ui_mascot_suit_value_description, hex)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.general_apply_action)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDefault) {
                    Text(stringResource(R.string.ui_mascot_suit_default_action))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.general_cancel_action))
                }
            }
        },
    )
}

/**
 * The pointer affordance. It carries no semantics of its own - [ChannelSlider] is the route for
 * anyone not dragging a finger across it.
 */
@Composable
private fun SaturationBrightnessField(
    modifier: Modifier = Modifier,
    picked: Hsv,
    onPickedChange: (Hsv) -> Unit,
) {
    val markerColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(picked.hue) {
                fun report(position: Offset) = onPickedChange(
                    picked.copy(
                        saturation = (position.x / size.width).coerceIn(0f, 1f),
                        brightness = 1f - (position.y / size.height).coerceIn(0f, 1f),
                    )
                )
                awaitEachGesture {
                    val down = awaitFirstDown()
                    report(down.position)
                    drag(down.id) { change ->
                        change.consume()
                        report(change.position)
                    }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(picked.hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawCircle(
            color = markerColor,
            radius = 6.dp.toPx(),
            center = Offset(size.width * picked.saturation, size.height * (1f - picked.brightness)),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun ChannelSlider(
    modifier: Modifier = Modifier,
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private data class Hsv(
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
)

private fun Int.toHsv(): Hsv {
    val channels = FloatArray(3)
    AndroidColor.colorToHSV(this or 0xFF000000.toInt(), channels)
    return Hsv(channels[0], channels[1], channels[2])
}

private fun Hsv.toRgb(): Int = Color.hsv(hue, saturation, brightness).toArgb() and 0xFFFFFF

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MascotSuitColorDialogPreview() {
    MascotSuitColorDialog(suitColor = null, onColorSelected = {}, onDismiss = {})
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MascotSuitColorDialogOverriddenPreview() {
    MascotSuitColorDialog(suitColor = 0x8C1C13, onColorSelected = {}, onDismiss = {})
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MascotSuitColorDialogPickingPreview() {
    MascotSuitColorDialogContent(
        picked = Hsv(hue = 268f, saturation = 0.62f, brightness = 0.44f),
        onPickedChange = {},
        onConfirm = {},
        onDefault = {},
        onDismiss = {},
    )
}

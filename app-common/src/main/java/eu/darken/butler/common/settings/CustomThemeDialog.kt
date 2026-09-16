package eu.darken.butler.common.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.theming.ThemeColorProvider
import eu.darken.butler.common.theming.ThemePalette
import eu.darken.butler.common.theming.ThemeSeed
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.ThemeStyle
import java.util.Locale

/**
 * Picks the seed the whole scheme is generated from, plus the palette style that decides how much
 * chroma the other roles take from it.
 */
@Composable
fun CustomThemeDialog(
    seed: Int?,
    palette: ThemePalette,
    style: ThemeStyle,
    onConfirm: (Int?, ThemePalette) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(seed) { (seed ?: ThemeState.DEFAULT_CUSTOM_SEED).toHsv() }
    var picked by remember(initial) { mutableStateOf(initial) }
    var pickedPalette by remember(palette) { mutableStateOf(palette) }

    CustomThemeDialogContent(
        picked = picked,
        onPickedChange = { picked = it },
        palette = pickedPalette,
        onPaletteChange = { pickedPalette = it },
        style = style,
        onConfirm = { onConfirm(picked.toRgb(), pickedPalette) },
        onDefault = { onConfirm(null, ThemePalette.TONAL_SPOT) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun CustomThemeDialogContent(
    picked: Hsv,
    onPickedChange: (Hsv) -> Unit,
    palette: ThemePalette,
    onPaletteChange: (ThemePalette) -> Unit,
    style: ThemeStyle,
    onConfirm: () -> Unit,
    onDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val seedColor = Color.hsv(picked.hue, picked.saturation, picked.brightness)
    val hex = "#%06X".format(Locale.ROOT, seedColor.toArgb() and 0xFFFFFF)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val preview = remember(seedColor, palette, style, isDark) {
        ThemeColorProvider.getColorScheme(
            seed = ThemeSeed(seedColor, palette),
            style = style,
            dark = isDark,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.ui_theme_custom_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        preview.primary,
                        preview.secondary,
                        preview.tertiary,
                        preview.surface,
                        preview.surfaceContainerHigh,
                    ).forEach { role ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(role)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp),
                                ),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.ui_theme_custom_seed_label),
                    style = MaterialTheme.typography.titleSmall,
                )

                Spacer(Modifier.height(8.dp))

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

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.ui_theme_custom_palette_label),
                    style = MaterialTheme.typography.titleSmall,
                )

                ThemePalette.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPaletteChange(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == palette,
                            onClick = { onPaletteChange(option) },
                        )
                        Text(
                            text = option.label.get(context),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.general_apply_action)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDefault) {
                    Text(stringResource(R.string.ui_theme_custom_default_action))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.general_cancel_action))
                }
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CustomThemeDialogPreview() {
    CustomThemeDialog(
        seed = null,
        palette = ThemePalette.TONAL_SPOT,
        style = ThemeStyle.DEFAULT,
        onConfirm = { _, _ -> },
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CustomThemeDialogMonochromePreview() {
    CustomThemeDialog(
        seed = 0x1565C0,
        palette = ThemePalette.MONOCHROME,
        style = ThemeStyle.HIGH_CONTRAST,
        onConfirm = { _, _ -> },
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CustomThemeDialogVibrantPreview() {
    CustomThemeDialog(
        seed = 0x8C1C13,
        palette = ThemePalette.VIBRANT,
        style = ThemeStyle.DEFAULT,
        onConfirm = { _, _ -> },
        onDismiss = {},
    )
}

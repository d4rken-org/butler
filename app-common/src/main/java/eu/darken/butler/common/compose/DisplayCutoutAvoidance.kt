package eu.darken.butler.common.compose

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the UI has to keep clear of the display cutout, user-controlled via settings.
 *
 * Defaults to `true` so previews, tests and anything composed outside the main activity behave like
 * the shipped default.
 */
val LocalAvoidDisplayCutout = compositionLocalOf { true }

/**
 * System bar insets, extended by the display cutout while [LocalAvoidDisplayCutout] is on.
 *
 * Single source of truth for cutout-aware horizontal insets: new call sites go through this instead
 * of unioning [WindowInsets.displayCutout] themselves, otherwise they silently ignore the setting.
 */
@Composable
fun systemBarsWithOptionalCutout(): WindowInsets {
    val systemBars = WindowInsets.systemBars
    val cutout = WindowInsets.displayCutout
    return if (LocalAvoidDisplayCutout.current) systemBars.union(cutout) else systemBars
}

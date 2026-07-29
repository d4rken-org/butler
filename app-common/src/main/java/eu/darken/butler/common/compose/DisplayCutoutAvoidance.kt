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
 * Defaults to `false` so previews, tests and anything composed outside the main activity behave like
 * the shipped default: draw into the cutout until the user opts into avoiding it.
 */
val LocalAvoidDisplayCutout = compositionLocalOf { false }

/**
 * Replaces the window's system bar insets for everything composed below it.
 *
 * Null in production, where the real window is the only source. The Play Store screenshot renders
 * provide one: layoutlib reports every window inset as zero and never paints the system bars, so
 * those renders draw their own and hand the matching values back through here.
 *
 * It lives in `app-common` because the consumers span layers - the navigation rail insets itself
 * through [systemBarsWithOptionalCutout], while the pane system and the floating bar stacks read
 * the status/navigation bars directly.
 */
val LocalSystemBarInsetsOverride = compositionLocalOf<WindowInsets?> { null }

/**
 * System bar insets, extended by the display cutout while [LocalAvoidDisplayCutout] is on.
 *
 * Single source of truth for cutout-aware horizontal insets: new call sites go through this instead
 * of unioning [WindowInsets.displayCutout] themselves, otherwise they silently ignore the setting.
 */
@Composable
fun systemBarsWithOptionalCutout(): WindowInsets {
    val systemBars = LocalSystemBarInsetsOverride.current ?: WindowInsets.systemBars
    val cutout = WindowInsets.displayCutout
    return if (LocalAvoidDisplayCutout.current) systemBars.union(cutout) else systemBars
}

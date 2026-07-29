package eu.darken.butler.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.BatteryFull
import androidx.compose.material.icons.twotone.SignalCellular4Bar
import androidx.compose.material.icons.twotone.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.LocalSystemBarInsetsOverride

/**
 * Height of the synthetic status bar. Also the top inset handed to [LocalSystemBarInsetsOverride].
 */
internal val ScreenshotStatusBarHeight = 28.dp

/**
 * Height of the synthetic gesture navigation area, and the bottom inset that goes with it.
 */
internal val ScreenshotNavBarHeight = 24.dp

/**
 * Draws the system bars over [content] and feeds the matching insets back into the pane system.
 *
 * Both halves are necessary. layoutlib ignores `showSystemUi` in a screenshot-test render - neither
 * a `navigation=`/`cutout=` device spec nor a `parent=` device makes it paint the bars - and it
 * reports every window inset as zero, so without this the panes start at y=0 and draw where the
 * status bar belongs.
 *
 * Drawing our own is what Play asks for anyway: the store's asset guidance wants a clean
 * notification bar with no carrier text, no notifications and battery/wifi/signal shown full, which
 * a real device screenshot cannot guarantee. It also keeps all 68 locales byte-identical here.
 *
 * The bars are an overlay, not padding: page content is supposed to scroll under them, exactly as
 * it does edge-to-edge on a device. Only the pane chrome insets itself, via the override. Content
 * that has no inset consumer of its own needs [screenshotSystemBarPadding] instead.
 */
@Composable
internal fun ScreenshotSystemBars(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val insets = remember(density) {
        with(density) {
            WindowInsets(
                left = 0,
                top = ScreenshotStatusBarHeight.roundToPx(),
                right = 0,
                bottom = ScreenshotNavBarHeight.roundToPx(),
            )
        }
    }
    CompositionLocalProvider(LocalSystemBarInsetsOverride provides insets) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            StatusBar(modifier = Modifier.align(Alignment.TopCenter))
            GestureHandle(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Insets full-window content away from the synthetic bars.
 *
 * For screens that reach the window edges without going through a pane, so
 * [LocalSystemBarInsetsOverride] never reaches them. The workspace manager is the case that matters:
 * it insets itself through a `Scaffold`, and a `Scaffold` reads `WindowInsets.systemBars` directly,
 * which layoutlib pins to zero.
 */
internal fun Modifier.screenshotSystemBarPadding(): Modifier =
    padding(top = ScreenshotStatusBarHeight, bottom = ScreenshotNavBarHeight)

@Composable
private fun StatusBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ScreenshotStatusBarHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Deliberately not localized: a locale-formatted clock would differ across the 68 renders
        // for no gain, and "12:30" reads correctly as both 12h and 24h. The Row mirrors itself
        // under RTL, which is what a real status bar does too.
        Text(
            text = "12:30",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(Icons.TwoTone.SignalCellular4Bar)
            StatusIcon(Icons.TwoTone.Wifi)
            StatusIcon(Icons.TwoTone.BatteryFull)
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector) = Icon(
    imageVector = icon,
    contentDescription = null,
    modifier = Modifier.size(14.dp),
    tint = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun GestureHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ScreenshotNavBarHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(108.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)),
        )
    }
}

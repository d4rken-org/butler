package eu.darken.butler.apps.ui.apps.items

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/**
 * Painter shown while an app icon is still being resolved off the main thread, and when
 * resolving it failed. Matches the [Icons.TwoTone.Android] fallback used for packages that
 * expose no icon at all, so an icon slot is never empty.
 */
@Composable
fun rememberAppIconFallbackPainter(): Painter {
    val vector = rememberVectorPainter(Icons.TwoTone.Android)
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(vector, tint) { TintedPainter(vector, tint) }
}

private class TintedPainter(
    private val delegate: Painter,
    private val tint: Color,
) : Painter() {

    override val intrinsicSize: Size
        get() = delegate.intrinsicSize

    override fun DrawScope.onDraw() {
        with(delegate) { draw(size, colorFilter = ColorFilter.tint(tint)) }
    }
}

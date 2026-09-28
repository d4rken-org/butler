package eu.darken.butler.common.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Lays a file path out left to right regardless of the UI language.
 *
 * Under an Arabic or Persian layout a plain `Text` is a right-to-left paragraph, so
 * `/storage/emulated/0/Download` renders as `storage/emulated/0/Download/`: the leading `/` is
 * neutral and lands on the paragraph's start edge. Forcing [textDirection] fixes the order, but it
 * also turns `TextAlign.Start` into physical left, so the alignment (argument, else the receiver's
 * own, else Start) is resolved against [layoutDirection] instead and the path stays on the edge
 * where the surrounding UI starts.
 *
 * Pass [TextDirection.Content] for a value that is only sometimes a path, e.g. a label or a query.
 */
fun TextStyle.asPathStyle(
    layoutDirection: LayoutDirection,
    textAlign: TextAlign? = null,
    textDirection: TextDirection = TextDirection.Ltr,
): TextStyle {
    val align = textAlign ?: this.textAlign.takeUnless { it == TextAlign.Unspecified } ?: TextAlign.Start
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val resolved = when (align) {
        TextAlign.Start -> if (isRtl) TextAlign.Right else TextAlign.Left
        TextAlign.End -> if (isRtl) TextAlign.Left else TextAlign.Right
        else -> align
    }
    return copy(textDirection = textDirection, textAlign = resolved)
}

@Composable
fun TextStyle.asPathStyle(textAlign: TextAlign? = null): TextStyle =
    asPathStyle(LocalLayoutDirection.current, textAlign)

@Composable
fun TextStyle.asMaybePathStyle(textAlign: TextAlign? = null): TextStyle =
    asPathStyle(LocalLayoutDirection.current, textAlign, TextDirection.Content)

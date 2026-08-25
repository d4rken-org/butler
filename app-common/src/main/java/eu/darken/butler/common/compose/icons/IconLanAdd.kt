package eu.darken.butler.common.compose.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2

/**
 * The twotone `lan` glyph, shrunk into the upper left so a plus fits beside it in the lower right.
 *
 * The two glyph paths are the Material ones verbatim; the group scales them to 3/4 so the badge
 * never overlaps them (the glyph ends at 15.75/16.5, the plus starts at 16/16).
 */
val Icons.TwoTone.LanAdd: ImageVector
    get() {
        _lanAdd?.let { return it }

        return ImageVector.Builder(
            name = "TwoTone.LanAdd",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            group(scaleX = 0.75f, scaleY = 0.75f) {
                path(fill = SolidColor(Color.Black), fillAlpha = 0.3f, strokeAlpha = 0.3f) {
                    moveTo(10.0f, 7.0f)
                    verticalLineTo(4.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineTo(10.0f)
                    close()
                    moveTo(9.0f, 17.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineTo(5.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineTo(9.0f)
                    close()
                    moveTo(19.0f, 17.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineTo(19.0f)
                    close()
                }
                path(fill = SolidColor(Color.Black)) {
                    moveTo(13.0f, 22.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-7.0f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineToRelative(-5.0f)
                    verticalLineTo(9.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineTo(2.0f)
                    horizontalLineTo(8.0f)
                    verticalLineToRelative(7.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineTo(6.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineTo(3.0f)
                    verticalLineToRelative(7.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-7.0f)
                    horizontalLineTo(8.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineTo(22.0f)
                    close()
                    moveTo(10.0f, 7.0f)
                    verticalLineTo(4.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineTo(10.0f)
                    close()
                    moveTo(9.0f, 17.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineTo(5.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineTo(9.0f)
                    close()
                    moveTo(19.0f, 17.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(-3.0f)
                    horizontalLineTo(19.0f)
                    close()
                }
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.0f, 16.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(3.0f)
                close()
            }
        }.build().also { _lanAdd = it }
    }

private var _lanAdd: ImageVector? = null

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LanAddPreview() {
    Icon(
        imageVector = Icons.TwoTone.LanAdd,
        contentDescription = null,
    )
}

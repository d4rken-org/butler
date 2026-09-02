/*
 * Material Symbols, "folder_shared" with the "+" badge of "add_home".
 *
 * Copyright Google LLC, licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Composed here because Material Symbols has no folder_shared variant carrying a plus.
 */
package eu.darken.butler.common.compose.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Granting access to a storage location, as opposed to creating a folder (`CreateNewFolder`).
 *
 * folder_shared already fills its lower right with the person glyph, so the badge cannot overlap it.
 * The folder is scaled to 75% and pushed to the upper left to clear the corner instead.
 */
val Icons.TwoTone.FolderSharedAdd: ImageVector
    get() {
        _folderSharedAdd?.let { return it }
        return ImageVector.Builder(
            name = "TwoTone.FolderSharedAdd",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f,
        ).apply {
            // Google's path data verbatim, so it keeps its own "0 -960 960 960" viewBox.
            group(scaleX = 0.75f, scaleY = 0.75f, translationX = -30f, translationY = 670f) {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(440f, -280f)
                    horizontalLineToRelative(320f)
                    verticalLineToRelative(-22f)
                    quadToRelative(0f, -45f, -44f, -71.5f)
                    reflectiveQuadTo(600f, -400f)
                    quadToRelative(-72f, 0f, -116f, 26.5f)
                    reflectiveQuadTo(440f, -302f)
                    verticalLineToRelative(22f)
                    close()
                    moveTo(600f, -440f)
                    quadToRelative(33f, 0f, 56.5f, -23.5f)
                    reflectiveQuadTo(680f, -520f)
                    quadToRelative(0f, -33f, -23.5f, -56.5f)
                    reflectiveQuadTo(600f, -600f)
                    quadToRelative(-33f, 0f, -56.5f, 23.5f)
                    reflectiveQuadTo(520f, -520f)
                    quadToRelative(0f, 33f, 23.5f, 56.5f)
                    reflectiveQuadTo(600f, -440f)
                    close()
                    moveTo(160f, -160f)
                    quadToRelative(-33f, 0f, -56.5f, -23.5f)
                    reflectiveQuadTo(80f, -240f)
                    verticalLineToRelative(-480f)
                    quadToRelative(0f, -33f, 23.5f, -56.5f)
                    reflectiveQuadTo(160f, -800f)
                    horizontalLineToRelative(240f)
                    lineToRelative(80f, 80f)
                    horizontalLineToRelative(320f)
                    quadToRelative(33f, 0f, 56.5f, 23.5f)
                    reflectiveQuadTo(880f, -640f)
                    verticalLineToRelative(400f)
                    quadToRelative(0f, 33f, -23.5f, 56.5f)
                    reflectiveQuadTo(800f, -160f)
                    horizontalLineTo(160f)
                    close()
                    moveTo(160f, -240f)
                    horizontalLineToRelative(640f)
                    verticalLineToRelative(-400f)
                    horizontalLineTo(447f)
                    lineToRelative(-80f, -80f)
                    horizontalLineTo(160f)
                    verticalLineToRelative(480f)
                    close()
                }
            }
            // Even-odd knocks the plus out of the disc without depending on subpath winding.
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(570f, 750f)
                arcTo(180f, 180f, 0f, isMoreThanHalf = true, isPositiveArc = true, 930f, 750f)
                arcTo(180f, 180f, 0f, isMoreThanHalf = true, isPositiveArc = true, 570f, 750f)
                close()
                moveTo(732f, 642f)
                horizontalLineTo(768f)
                verticalLineTo(732f)
                horizontalLineTo(858f)
                verticalLineTo(768f)
                horizontalLineTo(768f)
                verticalLineTo(858f)
                horizontalLineTo(732f)
                verticalLineTo(768f)
                horizontalLineTo(642f)
                verticalLineTo(732f)
                horizontalLineTo(732f)
                close()
            }
        }.build().also { _folderSharedAdd = it }
    }

private var _folderSharedAdd: ImageVector? = null

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FolderSharedAddPreview() {
    Icon(imageVector = Icons.TwoTone.FolderSharedAdd, contentDescription = null)
}

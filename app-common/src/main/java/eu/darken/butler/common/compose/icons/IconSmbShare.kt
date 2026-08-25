/*
 * Material Symbols, "smb_share".
 *
 * Copyright Google LLC, licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Vendored because the icon is not part of the material-icons-extended artifact.
 */
package eu.darken.butler.common.compose.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/** A shared folder on a server: one stored network location. The Network section itself keeps the Lan glyph. */
val Icons.TwoTone.SmbShare: ImageVector
    get() {
        _smbShare?.let { return it }
        return ImageVector.Builder(
            name = "TwoTone.SmbShare",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(485f, 520f)
                horizontalLineToRelative(163f)
                quadToRelative(26f, 0f, 44f, -18f)
                reflectiveQuadToRelative(18f, -44f)
                quadToRelative(0f, -26f, -18f, -44.5f)
                reflectiveQuadTo(648f, 395f)
                horizontalLineToRelative(-2f)
                quadToRelative(-5f, -32f, -29f, -53.5f)
                reflectiveQuadTo(560f, 320f)
                quadToRelative(-26f, 0f, -47f, 13.5f)
                reflectiveQuadTo(481f, 370f)
                quadToRelative(-30f, 2f, -50.5f, 23.5f)
                reflectiveQuadTo(410f, 445f)
                quadToRelative(0f, 30f, 21.5f, 52.5f)
                reflectiveQuadTo(485f, 520f)
                close()
                moveTo(120f, 840f)
                quadToRelative(-33f, 0f, -56.5f, -23.5f)
                reflectiveQuadTo(40f, 760f)
                verticalLineToRelative(-480f)
                quadToRelative(0f, -17f, 11.5f, -28.5f)
                reflectiveQuadTo(80f, 240f)
                quadToRelative(17f, 0f, 28.5f, 11.5f)
                reflectiveQuadTo(120f, 280f)
                verticalLineToRelative(480f)
                horizontalLineToRelative(640f)
                quadToRelative(17f, 0f, 28.5f, 11.5f)
                reflectiveQuadTo(800f, 800f)
                quadToRelative(0f, 17f, -11.5f, 28.5f)
                reflectiveQuadTo(760f, 840f)
                horizontalLineTo(120f)
                close()
                moveToRelative(160f, -160f)
                quadToRelative(-33f, 0f, -56.5f, -23.5f)
                reflectiveQuadTo(200f, 600f)
                verticalLineToRelative(-440f)
                quadToRelative(0f, -33f, 23.5f, -56.5f)
                reflectiveQuadTo(280f, 80f)
                horizontalLineToRelative(167f)
                quadToRelative(16f, 0f, 30.5f, 6f)
                reflectiveQuadToRelative(25.5f, 17f)
                lineToRelative(57f, 57f)
                horizontalLineToRelative(280f)
                quadToRelative(33f, 0f, 56.5f, 23.5f)
                reflectiveQuadTo(920f, 240f)
                verticalLineToRelative(360f)
                quadToRelative(0f, 33f, -23.5f, 56.5f)
                reflectiveQuadTo(840f, 680f)
                horizontalLineTo(280f)
                close()
            }
        }.build().also { _smbShare = it }
    }

private var _smbShare: ImageVector? = null

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SmbSharePreview() {
    Icon(imageVector = Icons.TwoTone.SmbShare, contentDescription = null)
}

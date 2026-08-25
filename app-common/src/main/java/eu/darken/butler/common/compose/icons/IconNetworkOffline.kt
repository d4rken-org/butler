/*
 * Phosphor Icons, "network-x".
 *
 * Copyright (c) 2020 Phosphor Icons, licensed under the MIT License.
 * https://github.com/phosphor-icons/homepage/blob/master/LICENSE
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

/** A server that did not answer: shown on a network location whose port is unreachable. */
val Icons.TwoTone.NetworkOffline: ImageVector
    get() {
        _networkOffline?.let { return it }
        return ImageVector.Builder(
            name = "TwoTone.NetworkOffline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 256f,
            viewportHeight = 256f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(232f, 112f)
                horizontalLineTo(136f)
                verticalLineTo(88f)
                horizontalLineToRelative(8f)
                arcToRelative(16f, 16f, 0f, false, false, 16f, -16f)
                verticalLineTo(40f)
                arcToRelative(16f, 16f, 0f, false, false, -16f, -16f)
                horizontalLineTo(112f)
                arcTo(16f, 16f, 0f, false, false, 96f, 40f)
                verticalLineTo(72f)
                arcToRelative(16f, 16f, 0f, false, false, 16f, 16f)
                horizontalLineToRelative(8f)
                verticalLineToRelative(24f)
                horizontalLineTo(24f)
                arcToRelative(8f, 8f, 0f, false, false, 0f, 16f)
                horizontalLineTo(56f)
                verticalLineToRelative(32f)
                horizontalLineTo(48f)
                arcToRelative(16f, 16f, 0f, false, false, -16f, 16f)
                verticalLineToRelative(32f)
                arcToRelative(16f, 16f, 0f, false, false, 16f, 16f)
                horizontalLineTo(80f)
                arcToRelative(16f, 16f, 0f, false, false, 16f, -16f)
                verticalLineTo(176f)
                arcToRelative(16f, 16f, 0f, false, false, -16f, -16f)
                horizontalLineTo(72f)
                verticalLineTo(128f)
                horizontalLineTo(184f)
                verticalLineToRelative(16f)
                arcToRelative(8f, 8f, 0f, false, false, 16f, 0f)
                verticalLineTo(128f)
                horizontalLineToRelative(32f)
                arcToRelative(8f, 8f, 0f, false, false, 0f, -16f)
                close()
                moveTo(112f, 40f)
                horizontalLineToRelative(32f)
                verticalLineTo(72f)
                horizontalLineTo(112f)
                close()
                moveTo(80f, 208f)
                horizontalLineTo(48f)
                verticalLineTo(176f)
                horizontalLineTo(80f)
                close()
                moveToRelative(141.65f, -34.34f)
                lineTo(203.31f, 192f)
                lineToRelative(18.35f, 18.34f)
                arcToRelative(8f, 8f, 0f, false, true, -11.32f, 11.32f)
                lineTo(192f, 203.31f)
                lineToRelative(-18.34f, 18.35f)
                arcToRelative(8f, 8f, 0f, false, true, -11.32f, -11.32f)
                lineTo(180.69f, 192f)
                lineToRelative(-18.35f, -18.34f)
                arcToRelative(8f, 8f, 0f, false, true, 11.32f, -11.32f)
                lineTo(192f, 180.69f)
                lineToRelative(18.34f, -18.35f)
                arcToRelative(8f, 8f, 0f, false, true, 11.32f, 11.32f)
                close()
            }
        }.build().also { _networkOffline = it }
    }

private var _networkOffline: ImageVector? = null

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun NetworkOfflinePreview() {
    Icon(imageVector = Icons.TwoTone.NetworkOffline, contentDescription = null)
}

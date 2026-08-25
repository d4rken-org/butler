/*
 * Bootstrap Icons, "hdd-network".
 *
 * Copyright (c) 2019-2024 The Bootstrap Authors, licensed under the MIT License.
 * https://github.com/twbs/icons/blob/main/LICENSE
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

/** A server that answered: shown on a network location whose port is reachable. */
val Icons.TwoTone.NetworkOnline: ImageVector
    get() {
        _networkOnline?.let { return it }
        return ImageVector.Builder(
            name = "TwoTone.NetworkOnline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 16f,
            viewportHeight = 16f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(2f, 2f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
                verticalLineToRelative(1f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
                horizontalLineToRelative(5.5f)
                verticalLineToRelative(3f)
                arcTo(1.5f, 1.5f, 0f, false, false, 6f, 11.5f)
                horizontalLineTo(0.5f)
                arcToRelative(0.5f, 0.5f, 0f, false, false, 0f, 1f)
                horizontalLineTo(6f)
                arcTo(1.5f, 1.5f, 0f, false, false, 7.5f, 14f)
                horizontalLineToRelative(1f)
                arcToRelative(1.5f, 1.5f, 0f, false, false, 1.5f, -1.5f)
                horizontalLineToRelative(5.5f)
                arcToRelative(0.5f, 0.5f, 0f, false, false, 0f, -1f)
                horizontalLineTo(10f)
                arcTo(1.5f, 1.5f, 0f, false, false, 8.5f, 10f)
                verticalLineTo(7f)
                horizontalLineTo(14f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
                verticalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, -2f)
                close()
                moveToRelative(0.5f, 3f)
                arcToRelative(0.5f, 0.5f, 0f, true, true, 0f, -1f)
                arcToRelative(0.5f, 0.5f, 0f, false, true, 0f, 1f)
                moveToRelative(2f, 0f)
                arcToRelative(0.5f, 0.5f, 0f, true, true, 0f, -1f)
                arcToRelative(0.5f, 0.5f, 0f, false, true, 0f, 1f)
            }
        }.build().also { _networkOnline = it }
    }

private var _networkOnline: ImageVector? = null

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun NetworkOnlinePreview() {
    Icon(imageVector = Icons.TwoTone.NetworkOnline, contentDescription = null)
}

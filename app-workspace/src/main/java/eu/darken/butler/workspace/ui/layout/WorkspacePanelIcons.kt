package eu.darken.butler.workspace.ui.layout

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.AutoAwesomeMosaic
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp


object WorkspacePanelIcons {
    val Auto: ImageVector
        get() = Icons.TwoTone.AutoAwesome

    val Adaptive: ImageVector
        get() = Icons.TwoTone.AutoAwesomeMosaic

    val Single: ImageVector
        get() = ImageVector.Builder(
            name = "LayoutSingle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(4f, 20f)
                close()
            }
        }.build()

    val SingleRail: ImageVector
        get() = ImageVector.Builder(
            name = "LayoutSingleRail",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 15f)
                lineTo(4f, 15f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 17f)
                lineTo(20f, 17f)
                lineTo(20f, 20f)
                lineTo(4f, 20f)
                close()
            }
        }.build()

    val SingleRailStart: ImageVector
        get() = ImageVector.Builder(
            name = "LayoutSingleRailStart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(7f, 4f)
                lineTo(7f, 20f)
                lineTo(4f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(9f, 20f)
                close()
            }
        }.build()

    val RailButtonBottomLeading: ImageVector
        get() = ImageVector.Builder(
            name = "RailButtonBottomLeading",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 15f)
                lineTo(4f, 15f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 17f)
                lineTo(7f, 17f)
                lineTo(7f, 20f)
                lineTo(4f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 17f)
                lineTo(20f, 17f)
                lineTo(20f, 20f)
                lineTo(9f, 20f)
                close()
            }
        }.build()

    val RailButtonBottomTrailing: ImageVector
        get() = ImageVector.Builder(
            name = "RailButtonBottomTrailing",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 15f)
                lineTo(4f, 15f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 17f)
                lineTo(15f, 17f)
                lineTo(15f, 20f)
                lineTo(4f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 17f)
                lineTo(20f, 17f)
                lineTo(20f, 20f)
                lineTo(17f, 20f)
                close()
            }
        }.build()

    val RailButtonSideLeading: ImageVector
        get() = ImageVector.Builder(
            name = "RailButtonSideLeading",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(9f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(7f, 4f)
                lineTo(7f, 7f)
                lineTo(4f, 7f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 9f)
                lineTo(7f, 9f)
                lineTo(7f, 20f)
                lineTo(4f, 20f)
                close()
            }
        }.build()

    val RailButtonSideTrailing: ImageVector
        get() = ImageVector.Builder(
            name = "RailButtonSideTrailing",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(9f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(7f, 4f)
                lineTo(7f, 15f)
                lineTo(4f, 15f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 17f)
                lineTo(7f, 17f)
                lineTo(7f, 20f)
                lineTo(4f, 20f)
                close()
            }
        }.build()

    val DualVertical: ImageVector
        get() = ImageVector.Builder(
            name = "LayoutDualVertical",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(11f, 4f)
                lineTo(11f, 20f)
                lineTo(4f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(13f, 20f)
                close()
            }
        }.build()

    val DualHorizontal: ImageVector
        get() = ImageVector.Builder(
            name = "LayoutDualHorizontal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 11f)
                lineTo(4f, 11f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 13f)
                lineTo(20f, 13f)
                lineTo(20f, 20f)
                lineTo(4f, 20f)
                close()
            }
        }.build()

    val TripleSidebarLeft: ImageVector
        get() = ImageVector.Builder(
            name = "LayoutTripleSidebarLeft",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(11f, 4f)
                lineTo(11f, 20f)
                lineTo(4f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 11f)
                lineTo(13f, 11f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 13f)
                lineTo(20f, 13f)
                lineTo(20f, 20f)
                lineTo(13f, 20f)
                close()
            }
        }.build()

    val TripleSidebarRight: ImageVector
        get() = ImageVector.Builder(
            name = "LayoutTripleSidebarRight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(11f, 4f)
                lineTo(11f, 11f)
                lineTo(4f, 11f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 13f)
                lineTo(11f, 13f)
                lineTo(11f, 20f)
                lineTo(4f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(13f, 20f)
                close()
            }
        }.build()

    val QuadGrid: ImageVector
        get() = ImageVector.Builder(
            name = "LayoutQuadGrid",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                lineTo(11f, 4f)
                lineTo(11f, 11f)
                lineTo(4f, 11f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 11f)
                lineTo(13f, 11f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 13f)
                lineTo(11f, 13f)
                lineTo(11f, 20f)
                lineTo(4f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 13f)
                lineTo(20f, 13f)
                lineTo(20f, 20f)
                lineTo(13f, 20f)
                close()
            }
        }.build()
}

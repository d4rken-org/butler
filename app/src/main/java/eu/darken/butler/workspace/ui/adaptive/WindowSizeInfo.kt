package eu.darken.butler.workspace.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

data class WindowSizeInfo(
    val widthDp: Dp,
    val heightDp: Dp,
    val widthSizeClass: WindowSizeClass,
    val heightSizeClass: WindowSizeClass,
) {
    val recommendedPaneCount: Int
        get() = when (widthSizeClass) {
            WindowSizeClass.COMPACT -> 1
            WindowSizeClass.MEDIUM -> if (heightSizeClass == WindowSizeClass.EXPANDED) 2 else 1
            WindowSizeClass.EXPANDED -> when (heightSizeClass) {
                WindowSizeClass.COMPACT -> 2
                WindowSizeClass.MEDIUM, WindowSizeClass.EXPANDED -> 3
            }
        }

    val recommendedPaneLayout: PaneLayout
        get() = when (recommendedPaneCount) {
            1 -> PaneLayout.SINGLE
            2 -> if (widthDp > heightDp) PaneLayout.DUAL_VERTICAL else PaneLayout.DUAL_HORIZONTAL
            3 -> PaneLayout.TRIPLE_MAIN_LEFT
            else -> PaneLayout.SINGLE
        }
}

@Composable
fun rememberWindowSizeInfo(): WindowSizeInfo {
    val configuration = LocalConfiguration.current
    LocalDensity.current

    val widthDp = configuration.screenWidthDp.dp
    val heightDp = configuration.screenHeightDp.dp

    val widthSizeClass = when {
        widthDp < 600.dp -> WindowSizeClass.COMPACT
        widthDp < 840.dp -> WindowSizeClass.MEDIUM
        else -> WindowSizeClass.EXPANDED
    }

    val heightSizeClass = when {
        heightDp < 480.dp -> WindowSizeClass.COMPACT
        heightDp < 900.dp -> WindowSizeClass.MEDIUM
        else -> WindowSizeClass.EXPANDED
    }

    return WindowSizeInfo(
        widthDp = widthDp,
        heightDp = heightDp,
        widthSizeClass = widthSizeClass,
        heightSizeClass = heightSizeClass,
    )
}
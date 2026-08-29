package eu.darken.butler.workspace.ui.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


data class WindowSizeInfo(
    val widthDp: Dp,
    val heightDp: Dp,
    val widthSizeClass: SizeClass,
    val heightSizeClass: SizeClass,
) {
    val recommendedPaneCount: Int
        get() = when (widthSizeClass) {
            SizeClass.COMPACT -> 1
            SizeClass.MEDIUM -> if (heightSizeClass == SizeClass.EXPANDED) 2 else 1
            SizeClass.EXPANDED -> when (heightSizeClass) {
                SizeClass.COMPACT -> 2
                SizeClass.MEDIUM, SizeClass.EXPANDED -> 3
            }
        }

    val recommendedLayout: WorkspaceDesign.Layout
        get() = when (recommendedPaneCount) {
            1 -> WorkspaceDesign.Layout.SINGLE
            2 -> if (widthDp > heightDp) WorkspaceDesign.Layout.DUAL_VERTICAL else WorkspaceDesign.Layout.DUAL_HORIZONTAL
            3 -> WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT
            else -> WorkspaceDesign.Layout.SINGLE
        }

    enum class SizeClass {
        COMPACT,
        MEDIUM,
        EXPANDED
    }
}

@Composable
fun rememberWindowSizeInfo(): WindowSizeInfo {
    val configuration = LocalConfiguration.current

    val widthDp = configuration.screenWidthDp.dp
    val heightDp = configuration.screenHeightDp.dp

    // Keyed on the measurements so the same instance survives recompositions at an unchanged size
    return remember(widthDp, heightDp) {
        val widthSizeClass = when {
            widthDp < 600.dp -> WindowSizeInfo.SizeClass.COMPACT
            widthDp < 840.dp -> WindowSizeInfo.SizeClass.MEDIUM
            else -> WindowSizeInfo.SizeClass.EXPANDED
        }

        val heightSizeClass = when {
            heightDp < 480.dp -> WindowSizeInfo.SizeClass.COMPACT
            heightDp < 900.dp -> WindowSizeInfo.SizeClass.MEDIUM
            else -> WindowSizeInfo.SizeClass.EXPANDED
        }

        WindowSizeInfo(
            widthDp = widthDp,
            heightDp = heightDp,
            widthSizeClass = widthSizeClass,
            heightSizeClass = heightSizeClass,
        )
    }
}
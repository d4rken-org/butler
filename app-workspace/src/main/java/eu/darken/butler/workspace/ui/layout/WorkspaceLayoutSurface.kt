package eu.darken.butler.workspace.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode

/** What Settings offers: which surface, not which geometry. */
enum class WorkspaceLayoutSurface {
    AUTOMATIC,
    CLASSIC,
    ADAPTIVE,
}

val WorkspacePanelMode.surface: WorkspaceLayoutSurface
    get() = when (this) {
        WorkspacePanelMode.AUTO -> WorkspaceLayoutSurface.AUTOMATIC
        WorkspacePanelMode.SINGLE -> WorkspaceLayoutSurface.CLASSIC
        else -> WorkspaceLayoutSurface.ADAPTIVE
    }

fun WorkspaceLayoutSurface.toPanelMode(): WorkspacePanelMode = when (this) {
    WorkspaceLayoutSurface.AUTOMATIC -> WorkspacePanelMode.AUTO
    WorkspaceLayoutSurface.CLASSIC -> WorkspacePanelMode.SINGLE
    WorkspaceLayoutSurface.ADAPTIVE -> WorkspacePanelMode.ADAPTIVE
}

@Composable
fun WorkspaceLayoutSurface.label(): String = when (this) {
    WorkspaceLayoutSurface.AUTOMATIC -> stringResource(R.string.workspace_settings_layout_mode_auto)
    WorkspaceLayoutSurface.CLASSIC -> stringResource(R.string.workspace_settings_layout_mode_classic)
    WorkspaceLayoutSurface.ADAPTIVE -> stringResource(R.string.workspace_settings_layout_mode_adaptive)
}

@Composable
fun WorkspaceLayoutSurface.description(): String = when (this) {
    WorkspaceLayoutSurface.AUTOMATIC -> stringResource(R.string.workspace_settings_layout_mode_auto_desc)
    WorkspaceLayoutSurface.CLASSIC -> stringResource(R.string.workspace_settings_layout_mode_classic_desc)
    WorkspaceLayoutSurface.ADAPTIVE -> stringResource(R.string.workspace_settings_layout_mode_adaptive_desc)
}

fun WorkspaceLayoutSurface.icon(): ImageVector = when (this) {
    WorkspaceLayoutSurface.AUTOMATIC -> WorkspacePanelIcons.Auto
    WorkspaceLayoutSurface.CLASSIC -> WorkspacePanelIcons.Single
    WorkspaceLayoutSurface.ADAPTIVE -> WorkspacePanelIcons.Adaptive
}

/** The geometries a rail user can pin; every one of them composes the rail. */
val ADAPTIVE_GEOMETRIES: List<WorkspacePanelMode> = listOf(
    WorkspacePanelMode.SINGLE_RAIL,
    WorkspacePanelMode.DUAL_VERTICAL,
    WorkspacePanelMode.DUAL_HORIZONTAL,
    WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT,
    WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT,
    WorkspacePanelMode.QUAD_GRID,
)

val WorkspacePanelMode.isPinnedGeometry: Boolean get() = this in ADAPTIVE_GEOMETRIES

/**
 * The geometries a window can host, from its long and short side so that rotating never changes
 * the set; only which dual splits the long side depends on orientation. The stored geometry is
 * always listed so the marked row stays visible after a window shrinks.
 */
fun offeredGeometries(width: Dp, height: Dp, stored: WorkspacePanelMode): List<WorkspacePanelMode> {
    val long = maxOf(width, height)
    val short = minOf(width, height)
    val budget: List<WorkspacePanelMode> = when {
        long < 840.dp -> listOf(WorkspacePanelMode.SINGLE_RAIL)
        short < 480.dp -> listOf(
            WorkspacePanelMode.SINGLE_RAIL,
            if (width > height) WorkspacePanelMode.DUAL_VERTICAL else WorkspacePanelMode.DUAL_HORIZONTAL,
        )
        short < 840.dp -> listOf(
            WorkspacePanelMode.SINGLE_RAIL,
            WorkspacePanelMode.DUAL_VERTICAL,
            WorkspacePanelMode.DUAL_HORIZONTAL,
            WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT,
            WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT,
        )
        else -> ADAPTIVE_GEOMETRIES
    }
    return ADAPTIVE_GEOMETRIES.filter { it in budget || it == stored }
}

/** The Settings item's value: the surface, with the pinned geometry named where there is one. */
@Composable
fun WorkspacePanelMode.surfaceValueLabel(): String = if (isPinnedGeometry) {
    stringResource(R.string.workspace_settings_layout_mode_adaptive_pinned_format, label())
} else {
    surface.label()
}

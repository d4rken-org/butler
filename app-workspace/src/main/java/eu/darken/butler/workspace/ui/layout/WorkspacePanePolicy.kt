package eu.darken.butler.workspace.ui.layout

import eu.darken.butler.workspace.core.layout.WorkspacePanelMode

/**
 * How many panes a mode pins. Null for the modes that follow whatever the window is recommended
 * ([WorkspacePanelMode.AUTO], [WorkspacePanelMode.ADAPTIVE]) rather than naming a count.
 */
val WorkspacePanelMode.paneCount: Int?
    get() = when (this) {
        WorkspacePanelMode.AUTO, WorkspacePanelMode.ADAPTIVE -> null
        WorkspacePanelMode.SINGLE, WorkspacePanelMode.SINGLE_RAIL -> 1
        WorkspacePanelMode.DUAL_VERTICAL, WorkspacePanelMode.DUAL_HORIZONTAL -> 2
        WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT, WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT -> 3
        WorkspacePanelMode.QUAD_GRID -> 4
    }

/**
 * Whether pinning this geometry needs Pro. Every window keeps the panes it is recommended for free;
 * what Pro buys is pinning more of them than that.
 *
 * On a 411x891dp phone the recommendation is 1, so both dual splits are Pro. On an 800x1280dp tablet
 * held in portrait it is 2, so the triples are. Nothing is gated on a 1280x800dp tablet in
 * landscape, and a phone whose long side is under 840dp is only ever offered SINGLE_RAIL.
 */
fun WorkspacePanelMode.requiresPro(recommendedPaneCount: Int): Boolean =
    isPinnedGeometry && (paneCount ?: return false) > recommendedPaneCount

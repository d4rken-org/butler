package eu.darken.butler.workspace.ui.dnd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import eu.darken.butler.common.files.APath

/** What the page's single drop target found under the pointer. */
sealed interface DropHit {

    /** A registered zone the payload may be dropped on. */
    data class Explicit(val destination: APath<*>) : DropHit

    /** A registered zone that refuses this payload; it never falls through to the pane below it. */
    data object Blocked : DropHit

    /** Content background, so the pane's own rules decide. */
    data object Pane : DropHit

    /**
     * Neither a zone nor content: floating bars, non-directory crumbs, overlays. A zone outside the
     * content band only counts if it registered with `allowOutsideContentBand`.
     */
    data object None : DropHit
}

/**
 * A zone outside [contentBand] is only a hit when it opted in, so rows scrolled behind a floating
 * bar stay out of reach of the pointer that is over the bar.
 *
 * @param contentBand the content bounds minus the floating-bar insets, i.e. the area where a drop
 *        means "the directory this page shows" rather than "the bar the pointer is over".
 */
fun resolveDropHit(
    positionInRoot: Offset,
    zones: (Offset) -> DropZoneRegistry.Zone?,
    contentBand: Rect,
    isValidExplicit: (APath<*>) -> Boolean,
): DropHit {
    val zone = zones(positionInRoot)
    if (zone != null && !zone.allowOutsideContentBand && !contentBand.contains(positionInRoot)) return DropHit.None
    if (zone != null) {
        return if (isValidExplicit(zone.destination)) DropHit.Explicit(zone.destination) else DropHit.Blocked
    }
    return if (contentBand.contains(positionInRoot)) DropHit.Pane else DropHit.None
}

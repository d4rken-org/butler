package eu.darken.butler.common.debug.logviewer.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Pure clamp math for the floating panel, extracted for unit testing.
 *
 * The minimum is softened against the container: in narrow multi-window/foldable panes the safe
 * drawing area can be smaller than the panel's nominal minimum, and a plain
 * `coerceIn(min, container)` would throw (`minimumValue > maximumValue`).
 */
internal object PanelGeometry {

    /** Clamp a desired panel dimension into [0-ish, container], honoring [minSize] only when it fits. */
    fun clampSize(desired: Float, minSize: Float, container: Float): Float {
        val effectiveMin = min(minSize, container)
        return desired.coerceIn(effectiveMin, max(container, effectiveMin))
    }

    /** Clamp a top-start offset so an element of [activeSize] stays fully inside [container]. */
    fun clampOffset(offset: Float, activeSize: Float, container: Float): Float =
        offset.coerceIn(0f, (container - activeSize).coerceAtLeast(0f))
}

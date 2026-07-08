package eu.darken.butler.common.coil

import coil3.request.Options
import coil3.size.Dimension

/**
 * Largest requested edge in pixels, or 0 when the size is undefined (e.g. Coil `Size.ORIGINAL`).
 * Preview generators feed this through [eu.darken.butler.common.files.preview.PreviewBudget] so an
 * undefined/original request can never drive an unbounded allocation.
 */
fun Options.targetEdgePx(): Int {
    val w = (size.width as? Dimension.Pixels)?.px ?: 0
    val h = (size.height as? Dimension.Pixels)?.px ?: 0
    return maxOf(w, h)
}

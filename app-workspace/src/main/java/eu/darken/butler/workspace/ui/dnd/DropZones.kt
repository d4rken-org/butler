package eu.darken.butler.workspace.ui.dnd

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import eu.darken.butler.common.files.APath

/**
 * Root-relative bounds of the individual drop destinations inside one page.
 *
 * A drag session only knows the targets that existed when it started, so a row scrolled in
 * mid-drag could never own a target of its own. The page keeps a single target instead and
 * hit-tests this registry on every move.
 */
@Stable
class DropZoneRegistry {

    data class Zone(
        val key: Any,
        val destination: APath<*>,
        val bounds: Rect,
        val allowOutsideContentBand: Boolean = false,
    )

    private val zones = mutableMapOf<Any, Zone>()

    /** The zone the drag currently rests on, so it can draw itself as hovered. */
    var hoveredKey: Any? by mutableStateOf(null)
        private set

    fun setHovered(key: Any?) {
        if (hoveredKey != key) hoveredKey = key
    }

    fun register(key: Any, destination: APath<*>, bounds: Rect, allowOutsideContentBand: Boolean = false) {
        zones[key] = Zone(
            key = key,
            destination = destination,
            bounds = bounds,
            allowOutsideContentBand = allowOutsideContentBand,
        )
    }

    fun unregister(key: Any) {
        zones.remove(key)
        if (hoveredKey == key) hoveredKey = null
    }

    /**
     * Zones may nest (a crumb inside a bar), so the smallest one containing the point wins.
     *
     * [isEligible] runs before that pick, so a zone the caller can't use never shadows one it can.
     */
    fun zoneAt(positionInRoot: Offset, isEligible: (Zone) -> Boolean = { true }): Zone? = zones.values
        .filter { it.bounds.contains(positionInRoot) && isEligible(it) }
        .minByOrNull { it.bounds.width * it.bounds.height }
}

/** The page's zone registry, or `null` where nothing hit-tests zones (previews, other pages). */
val LocalDropZoneRegistry = compositionLocalOf<DropZoneRegistry?> { null }

/**
 * Publishes this composable's bounds as a drop destination and draws the hover affordance.
 *
 * A null [destination] registers nothing, so a folder that turns read-only drops out of the
 * registry instead of accepting a drop it can't perform.
 */
@Composable
fun Modifier.dropZone(key: Any, destination: APath<*>?, allowOutsideContentBand: Boolean = false): Modifier {
    val registry = LocalDropZoneRegistry.current ?: return this

    DisposableEffect(registry, key, destination) {
        onDispose { registry.unregister(key) }
    }

    if (destination == null) return this
    return this
        .onGloballyPositioned { registry.register(key, destination, it.boundsInRoot(), allowOutsideContentBand) }
        .dropTargetHighlight(registry.hoveredKey == key)
}

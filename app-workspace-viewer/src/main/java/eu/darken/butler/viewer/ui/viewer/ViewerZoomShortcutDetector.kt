package eu.darken.butler.viewer.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEvent
import me.saket.telephoto.zoomable.HardwareShortcutDetector
import me.saket.telephoto.zoomable.HardwareShortcutDetector.ShortcutEvent
import me.saket.telephoto.zoomable.HardwareShortcutsSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Telephoto claims Left/Right as pan shortcuts even when there is nothing to pan, which swallows
 * the page's file stepping whenever the picture holds focus. Leaves them to the page until zoomed in.
 */
internal class ViewerZoomShortcutDetector : HardwareShortcutDetector {

    /** Assigned once the state this detector is attached to exists; null reads as not zoomed. */
    var zoomableState: ZoomableState? = null

    private val isZoomedIn: Boolean
        get() {
            val transformation = zoomableState?.contentTransformation ?: return false
            return isZoomedIn(
                transformationSpecified = transformation.isSpecified,
                userZoom = transformation.scaleMetadata.userZoom,
            )
        }

    override fun detectKey(event: KeyEvent): ShortcutEvent? {
        val horizontal = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
        if (horizontal && !isZoomedIn) return null
        return HardwareShortcutDetector.Default.detectKey(event)
    }

    override fun detectScroll(event: PointerEvent): ShortcutEvent? =
        HardwareShortcutDetector.Default.detectScroll(event)
}

@Composable
internal fun rememberViewerZoomableState(): ZoomableState {
    val detector = remember { ViewerZoomShortcutDetector() }
    val spec = remember(detector) { HardwareShortcutsSpec(shortcutDetector = detector) }
    return rememberZoomableState(hardwareShortcutsSpec = spec).also { detector.zoomableState = it }
}

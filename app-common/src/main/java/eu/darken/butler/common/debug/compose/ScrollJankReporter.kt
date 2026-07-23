package eu.darken.butler.common.debug.compose

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import kotlinx.coroutines.flow.collectLatest

/**
 * Debug-only scroll profiler. While [scrollable] is scrolling it measures the interval between
 * Compose frame callbacks and, when the scroll settles (or the composable is disposed), logs a
 * one-line summary per scroll session — gated on [Bugs.isTrace].
 *
 * This measures **frame-callback intervals**, not render-thread/GPU work, so it is a lightweight
 * jank proxy rather than an authoritative frame profiler (JankStats/FrameMetrics would be more
 * accurate). Buckets are refresh-rate agnostic: an interval > ~20 ms means a 60 Hz frame was
 * likely missed.
 *
 * The effect is installed for the composition lifetime and only checks [Bugs.isTrace] when a
 * session starts — do not add/remove this composable based on the flag (that would not be
 * reliably reactive).
 */
@Composable
fun ReportScrollJank(
    scrollable: ScrollableState,
    tag: String,
) {
    LaunchedEffect(scrollable, tag) {
        snapshotFlow { scrollable.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling || !Bugs.isTrace) return@collectLatest

            var frames = 0
            var jank20 = 0
            var jank34 = 0
            var jank50 = 0
            var worstNanos = 0L
            var totalNanos = 0L
            var lastNanos = 0L
            try {
                while (true) {
                    withFrameNanos { now ->
                        if (lastNanos != 0L) {
                            val delta = now - lastNanos
                            frames++
                            totalNanos += delta
                            if (delta > worstNanos) worstNanos = delta
                            val ms = delta / 1_000_000.0
                            if (ms > 20) jank20++
                            if (ms > 34) jank34++
                            if (ms > 50) jank50++
                        }
                        lastNanos = now
                    }
                }
            } finally {
                // Non-suspending: runs even when collectLatest cancels this block on scroll-stop.
                if (frames > 0) {
                    log(tag, VERBOSE) {
                        "scroll session: frames=$frames totalMs=${totalNanos / 1_000_000} " +
                            "worstMs=${worstNanos / 1_000_000} jank>20/34/50ms=$jank20/$jank34/$jank50"
                    }
                }
            }
        }
    }
}

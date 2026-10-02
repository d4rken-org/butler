package eu.darken.butler.e2e

import android.os.SystemClock
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2

internal fun <T> clickWhenSettled(
    selector: BySelector,
    timeoutMs: Long,
    findNode: (remainingMs: Long) -> UiObject2?,
    beforeClick: (UiObject2) -> T,
    now: () -> Long = SystemClock::uptimeMillis,
    pause: (Long) -> Unit = SystemClock::sleep,
): T {
    val deadline = now() + timeoutMs
    var lastStale: StaleObjectException? = null
    var timeoutReason = "target not found"

    fun remaining(): Long {
        val left = deadline - now()
        if (left <= 0) {
            throw AssertionError("Timed out after ${timeoutMs}ms clicking $selector: $timeoutReason", lastStale)
        }
        return left
    }

    fun poll() = pause(minOf(SETTLE_POLL_MS, remaining()))

    while (true) {
        try {
            val node = findNode(remaining())
            timeoutReason = if (node == null) "target not found" else "bounds not yet sampled"
            remaining()
            if (node != null) {
                var bounds = node.visibleBounds
                timeoutReason = "bounds never settled (last bounds: $bounds)"
                while (true) {
                    // Onboarding pages slide in; sample the same live node until its bounds settle.
                    poll()
                    remaining()
                    val current = node.visibleBounds
                    timeoutReason = "bounds never settled (last bounds: $current)"
                    remaining()
                    if (current == bounds && !current.isEmpty) break
                    bounds = current
                }
                timeoutReason = "bounds settled; click not completed"
                val result = beforeClick(node)
                remaining()
                node.click()
                return result
            }
        } catch (e: StaleObjectException) {
            // UiObject2.click refreshes its node before injecting the gesture, not after it.
            lastStale = e
            timeoutReason = "stale node retry"
        }
        poll()
    }
}

private const val SETTLE_POLL_MS = 150L

package eu.darken.butler.main.core.tracker

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.main.core.shortcuts.DynamicShortcutManager
import eu.darken.butler.workspace.core.tracker.PathAccessTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Singleton
class PathAccessTrackerImpl @Inject constructor(
    private val dynamicShortcutManager: DynamicShortcutManager,
) : PathAccessTracker {
    private var lastTrackedPath: APath<*>? = null
    private var lastTrackedTime: Instant? = null

    override suspend fun trackPathAccess(path: APath<*>) {
        val now = Clock.System.now()
        val shouldTrack = when {
            // Different path than last tracked
            lastTrackedPath != path -> true
            // Same path but more than debounce window has passed
            lastTrackedTime != null && (now - lastTrackedTime!!) > 5.seconds -> true
            // Otherwise don't track
            else -> false
        }

        if (shouldTrack) {
            try {
                log(TAG, DEBUG) { "Tracking path access: $path" }
                dynamicShortcutManager.trackDirectoryAccess(path)
                lastTrackedPath = path
                lastTrackedTime = now
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to track path access: ${e.asLog()}" }
            }
        } else {
            log(TAG, DEBUG) { "Skipping duplicate path tracking for: $path" }
        }
    }

    companion object {
        private val TAG = logTag("PathAccess", "Tracker")
    }
}
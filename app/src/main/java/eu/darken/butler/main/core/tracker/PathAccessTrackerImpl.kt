package eu.darken.butler.main.core.tracker

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.main.core.shortcuts.DynamicShortcutManager
import eu.darken.butler.workspace.core.tracker.PathAccessTracker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathAccessTrackerImpl @Inject constructor(
    private val dynamicShortcutManager: DynamicShortcutManager,
) : PathAccessTracker {

    override suspend fun trackPathAccess(path: APath) {
        log(TAG, DEBUG) { "Tracking path access: $path" }
        dynamicShortcutManager.trackDirectoryAccess(path)
    }

    companion object {
        private val TAG = logTag("PathAccess", "Tracker")
    }
}
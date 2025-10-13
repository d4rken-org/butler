package eu.darken.butler.main.core.shortcuts

import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
class ShortcutRepo @Inject constructor(
    private val settings: ShortcutSettings,
) {

    val topShortcuts: Flow<List<RecentPath>> = combine(
        settings.isEnabled.flow,
        settings.maxShortcuts.flow,
        settings.minAccessCount.flow,
        settings.lastAccessedData.flow,
    ) { isEnabled, maxCount, minAccess, lastAccessedData ->
        if (!isEnabled) return@combine emptyList()

        lastAccessedData.paths
            .filter { it.accessCount >= minAccess }
            .sortedWith(
                compareByDescending<RecentPath> { it.accessCount }
                    .thenByDescending { it.lastAccessed }
            )
            .take(maxCount)
    }

    suspend fun trackAccess(path: APath<*>) {
        try {
            if (!settings.autoRememberEnabled.value()) {
                log(TAG, DEBUG) { "Auto-remembering disabled, skipping: $path" }
                return
            }

            val currentData = settings.lastAccessedData.value()
            val updatedPaths = updateLastAccessedList(currentData.paths, path)
            val updatedData = currentData.copy(paths = updatedPaths)
            settings.lastAccessedData.value(updatedData)
            log(TAG, DEBUG) { "Remembered access to: $path" }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to remember access: ${e.asLog()}" }
        }
    }

    private fun updateLastAccessedList(
        current: List<RecentPath>,
        accessedPath: APath<*>,
    ): List<RecentPath> {
        val existing = current.find { it.path == accessedPath }
        val updated = existing?.copy(
            accessCount = existing.accessCount + 1,
            lastAccessed = kotlin.time.Clock.System.now(),
        ) ?: RecentPath(
            id = Uuid.random(),
            path = accessedPath,
        )

        val updatedPaths = (listOf(updated) + current.filter { it.path != accessedPath }).take(TRACKING_LIMIT)

        return updatedPaths
    }


    companion object {
        private val TAG = logTag("Shortcuts", "Repo")
        private const val TRACKING_LIMIT = 20
    }
}
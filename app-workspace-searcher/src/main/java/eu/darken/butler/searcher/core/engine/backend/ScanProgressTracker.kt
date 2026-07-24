package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.core.engine.SearchConfig

/**
 * Per-scan progress bookkeeping shared by all backends: throttled interval reporting via
 * [onItemScanned], error accounting via [recordError]/[recordAccessError], and a [flush] for the
 * final snapshot so totals are accurate between intervals. Not thread-safe; owned by a single scan
 * coroutine.
 */
internal class ScanProgressTracker(
    private val currentPath: APath<*>?,
    private val onProgress: (SearchBackend.ScanProgress) -> Unit,
) {
    var itemsScanned = 0
        private set
    var resultsFound = 0
        private set

    /**
     * Generic "result may be incomplete" signal: content searches degraded (overlong line / size
     * cap) on an otherwise readable file, and malformed index rows. NOT the same as an inaccessible
     * item — those go through [recordAccessError].
     */
    var errorCount = 0
        private set

    /** Items that could not be read at all (e.g. permission denied). Exact, uncapped. */
    var accessErrorCount = 0
        private set

    // Bounded sample of inaccessible paths for display; [accessErrorCount] stays exact.
    private val accessErrorPaths = mutableListOf<APath<*>>()

    fun onItemScanned() {
        itemsScanned++
        if (itemsScanned % SearchConfig.PROGRESS_UPDATE_INTERVAL == 0) {
            onProgress(snapshot())
        }
    }

    fun onResultFound() {
        resultsFound++
    }

    fun recordError(errorPath: APath<*>?) {
        errorCount++
    }

    fun recordAccessError(errorPath: APath<*>) {
        if (accessErrorPaths.size < SearchConfig.MAX_REPORTED_ERROR_PATHS) {
            // The same entry can be reported through more than one channel (e.g. a denied route
            // boundary AND its unreadable lookup) — count unique items, not report events.
            if (errorPath in accessErrorPaths) return
            accessErrorPaths.add(errorPath)
        }
        accessErrorCount++
    }

    fun flush() = onProgress(snapshot())

    private fun snapshot() = SearchBackend.ScanProgress(
        currentPath = currentPath,
        itemsScanned = itemsScanned,
        resultsFound = resultsFound,
        errorCount = errorCount,
        accessErrorCount = accessErrorCount,
        accessErrorPaths = accessErrorPaths.toList(),
    )
}

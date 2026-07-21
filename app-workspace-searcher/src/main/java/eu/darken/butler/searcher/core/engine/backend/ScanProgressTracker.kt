package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.core.engine.SearchConfig

/**
 * Per-scan progress bookkeeping shared by all backends: throttled interval reporting via
 * [onItemScanned], error accounting via [recordError], and a [flush] for the final snapshot so
 * totals are accurate between intervals. Not thread-safe; owned by a single scan coroutine.
 */
internal class ScanProgressTracker(
    private val currentPath: APath<*>?,
    private val onProgress: (SearchBackend.ScanProgress) -> Unit,
) {
    var itemsScanned = 0
        private set
    var resultsFound = 0
        private set
    var errorCount = 0
        private set
    private var firstErrorPath: APath<*>? = null

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
        if (firstErrorPath == null) firstErrorPath = errorPath
    }

    fun flush() = onProgress(snapshot())

    private fun snapshot() = SearchBackend.ScanProgress(
        currentPath = currentPath,
        itemsScanned = itemsScanned,
        resultsFound = resultsFound,
        errorCount = errorCount,
        firstErrorPath = firstErrorPath,
    )
}

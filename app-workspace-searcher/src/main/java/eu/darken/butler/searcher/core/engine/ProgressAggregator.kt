package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.files.APath
import java.util.concurrent.ConcurrentHashMap

internal class ProgressAggregator {
    private val pathProgress = ConcurrentHashMap<APath<*>, PathScanner.PathProgress>()

    fun update(path: APath<*>, progress: PathScanner.PathProgress) {
        pathProgress[path] = progress
    }

    fun createSnapshot(): AggregateProgress {
        val values = pathProgress.values
        return AggregateProgress(
            totalScanned = values.sumOf { it.itemsScanned },
            totalFound = values.sumOf { it.resultsFound },
            activePaths = pathProgress.keys.toList(),
            currentPath = values.maxByOrNull { it.itemsScanned }?.currentPath,
        )
    }

    data class AggregateProgress(
        val totalScanned: Int,
        val totalFound: Int,
        val activePaths: List<APath<*>>,
        val currentPath: APath<*>?,
    )
}

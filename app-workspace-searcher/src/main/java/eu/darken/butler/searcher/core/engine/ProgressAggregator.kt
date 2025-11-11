package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

internal class ProgressAggregator {
    private val pathProgress = ConcurrentHashMap<APath<*>, PathScanner.PathProgress>()

    private val _pathProgressFlow = MutableStateFlow<Map<APath<*>, PathScanner.PathProgress>>(emptyMap())
    val pathProgressFlow: StateFlow<Map<APath<*>, PathScanner.PathProgress>> = _pathProgressFlow.asStateFlow()

    fun update(path: APath<*>, progress: PathScanner.PathProgress) {
        pathProgress[path] = progress
        _pathProgressFlow.value = pathProgress.toMap() // Emit snapshot
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

package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.core.engine.backend.SearchBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

internal class ProgressAggregator {
    private val pathProgress = ConcurrentHashMap<APath<*>, SearchBackend.ScanProgress>()

    private val _pathProgressFlow = MutableStateFlow<Map<APath<*>, SearchBackend.ScanProgress>>(emptyMap())
    val pathProgressFlow: StateFlow<Map<APath<*>, SearchBackend.ScanProgress>> = _pathProgressFlow.asStateFlow()

    fun update(path: APath<*>, progress: SearchBackend.ScanProgress) {
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

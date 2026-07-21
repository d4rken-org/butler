package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.core.engine.backend.SearchBackend
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregates per-scan progress across concurrent targets. Keyed by target identity — targets
 * are normalized (identity-deduped) before scanning, so one scan maps to exactly one key.
 * [SearchBackend.ScanProgress.currentPath] is display-only and may be null (index-based
 * backends scan collections, not paths).
 */
internal class ProgressAggregator {
    private val progressByTarget = ConcurrentHashMap<Any, SearchBackend.ScanProgress>()

    fun update(target: SearchTarget, progress: SearchBackend.ScanProgress) {
        progressByTarget[target.identity] = progress
    }

    fun createSnapshot(): AggregateProgress {
        val values = progressByTarget.values
        return AggregateProgress(
            totalScanned = values.sumOf { it.itemsScanned },
            totalFound = values.sumOf { it.resultsFound },
            currentPath = values.maxByOrNull { it.itemsScanned }?.currentPath,
        )
    }

    data class AggregateProgress(
        val totalScanned: Int,
        val totalFound: Int,
        val currentPath: APath<*>?,
    )
}

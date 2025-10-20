package eu.darken.butler.searcher.core

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchEngine @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider
) {


    data class SearchProgress(
        val currentPath: APath<*>,
        val itemsScanned: Int,
        val resultsFound: Int
    )

    suspend fun search(
        searchQuery: SearchQuery,
        onProgress: ((SearchProgress) -> Unit)? = null
    ): Flow<SearchResult> = flow {
        // Filter to only enabled path targets
        val enabledTargets = searchQuery.targets.filterIsInstance<SearchTarget.Path>().filter { it.enabled }
        log(TAG) { "Starting search with query: ${searchQuery.query} across ${enabledTargets.size} enabled path target(s) (${searchQuery.targets.size} total)" }

        var itemsScanned = 0
        var resultsFound = 0

        // Search each enabled path target sequentially
        for (pathTarget in enabledTargets) {
            val searchPath = pathTarget.path
            if (!currentCoroutineContext().isActive) {
                log(TAG) { "Search cancelled" }
                throw CancellationException("Search cancelled")
            }

            log(TAG, INFO) { "Searching path: $searchPath" }

            try {
                when (val gateway = gatewaySwitch.getGateway(searchPath)) {
                    is APathGateway<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val typedGateway = gateway as APathGateway<APath<*>, APathLookup<APath<*>>>

                        val walkOptions = APathGateway.WalkOptions<APath<*>, APathLookup<APath<*>>>(
                            onFilter = { lookup ->
                                if (!currentCoroutineContext().isActive) throw CancellationException()

                                itemsScanned++

                                if (itemsScanned % 100 == 0) {
                                    onProgress?.invoke(
                                        SearchProgress(
                                            currentPath = searchPath,
                                            itemsScanned = itemsScanned,
                                            resultsFound = resultsFound
                                        )
                                    )
                                }

                                filterLookup(lookup, searchQuery.filter)
                            },
                            onError = { lookup, error ->
                                log(TAG, VERBOSE) { "Error accessing ${lookup.lookedUp}: $error" }
                                true // Continue walking
                            }
                        )

                        delay(3000)
                        typedGateway.walk(searchPath, walkOptions)
                            .cancellable()
                            .mapNotNull { lookup ->
                                if (matchesSearch(lookup, searchQuery)) {
                                    resultsFound++
                                    SearchResult.fromLookup(lookup, searchQuery.query)
                                } else {
                                    null
                                }
                            }
                            .onEach { result ->
                                if (searchQuery.options.maxResults != null && resultsFound >= searchQuery.options.maxResults) {
                                    log(TAG, INFO) { "Max results reached ($resultsFound)" }
                                    throw CancellationException("Max results reached")
                                }
                            }
                            .collect { emit(it) }
                    }
                }
                log(TAG, INFO) { "Completed search for path: $searchPath" }
            } catch (e: CancellationException) {
                // Re-throw cancellation to stop entire search
                throw e
            } catch (e: Exception) {
                // Log error but continue with next path
                log(TAG, WARN) { "Failed to search path $searchPath: ${e.message}" }
            }
        }

        log(TAG, INFO) { "Search completed. Scanned: $itemsScanned, Found: $resultsFound" }
    }.flowOn(dispatcherProvider.IO)

    private fun filterLookup(lookup: APathLookup<*>, filter: SearchQuery.Filter): Boolean {
        // File type filter
        if (filter.fileTypes != null && lookup.fileType !in filter.fileTypes) {
            return false
        }

        // Size filter
        if (filter.minSize != null && lookup.size?.let { it < filter.minSize } == true) return false
        if (filter.maxSize != null && lookup.size?.let { it > filter.maxSize } == true) return false

        // Modified date filter
        if (filter.modifiedAfter != null && lookup.modifiedAt?.let { it < filter.modifiedAfter } == true) {
            return false
        }
        if (filter.modifiedBefore != null && lookup.modifiedAt?.let { it > filter.modifiedBefore } == true) return false

        // Path filters
        val pathStr = lookup.path

        if (filter.excludePaths != null) {
            if (filter.excludePaths.any { pathStr.contains(it) }) return false
        }

        if (filter.includePaths != null) {
            if (filter.includePaths.none { pathStr.contains(it) }) return false
        }

        // Hidden files filter
        if (!filter.searchHidden && lookup.name.startsWith(".")) {
            return false
        }

        return true
    }

    private suspend fun matchesSearch(
        lookup: APathLookup<*>,
        searchQuery: SearchQuery
    ): Boolean = withContext(dispatcherProvider.Default) {
        val query = searchQuery.query
        val filter = searchQuery.filter

        // Name matching
        val name = lookup.name
        val matches = when {
            filter.useRegex -> {
                try {
                    val regex = if (filter.caseSensitive) {
                        query.toRegex()
                    } else {
                        query.toRegex(RegexOption.IGNORE_CASE)
                    }
                    regex.containsMatchIn(name)
                } catch (e: Exception) {
                    log(TAG, VERBOSE) { "Invalid regex: $query" }
                    false
                }
            }

            filter.wholeWord -> {
                val pattern = "\\b${Regex.escape(query)}\\b"
                val regex = if (filter.caseSensitive) {
                    pattern.toRegex()
                } else {
                    pattern.toRegex(RegexOption.IGNORE_CASE)
                }
                regex.containsMatchIn(name)
            }

            else -> {
                name.contains(query, ignoreCase = !filter.caseSensitive)
            }
        }

        matches
    }

    companion object {
        private val TAG = logTag("Searcher", "Engine")
    }
}
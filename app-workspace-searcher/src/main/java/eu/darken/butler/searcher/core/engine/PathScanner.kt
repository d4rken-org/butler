package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.MetadataRepo
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PathScanner @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val metadataRepo: MetadataRepo,
    private val dispatcherProvider: DispatcherProvider,
) {

    data class PathProgress(
        val currentPath: APath<*>,
        val itemsScanned: Int,
        val resultsFound: Int,
    )

    suspend fun scan(
        path: APath<*>,
        query: SearchQuery,
        onProgress: (PathProgress) -> Unit
    ): Flow<SearchItem> = flow {
        log(TAG, INFO) { "Scanning path: $path" }

        if (!currentCoroutineContext().isActive) {
            log(TAG) { "Scan cancelled before starting" }
            throw CancellationException("Scan cancelled")
        }

        var itemsScanned = 0
        var resultsFound = 0

        try {
            when (val gateway = gatewaySwitch.getGateway(path)) {
                is APathGateway<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val typedGateway = gateway as APathGateway<APath<*>, APathLookup<APath<*>>>

                    val walkOptions = APathGateway.WalkOptions<APath<*>, APathLookup<APath<*>>>(
                        onFilter = { lookup ->
                            if (!currentCoroutineContext().isActive) throw CancellationException()

                            itemsScanned++

                            if (itemsScanned % 100 == 0) {
                                onProgress(
                                    PathProgress(
                                        currentPath = path,
                                        itemsScanned = itemsScanned,
                                        resultsFound = resultsFound
                                    )
                                )
                            }

                            filterLookup(lookup, query.filter)
                        },
                        onError = { lookup, error ->
                            log(TAG, Logging.Priority.VERBOSE) { "Error accessing ${lookup.lookedUp}: $error" }
                            true // Continue walking
                        }
                    )

                    typedGateway.walk(path, LookupOptions.MAX, walkOptions)
                        .cancellable()
                        .mapNotNull { lookup ->
                            if (matchesSearch(lookup, query)) {
                                resultsFound++
                                val metadata = metadataRepo.extract(lookup)
                                SearchItem.fromLookup(lookup, query.query, metadata = metadata)
                            } else {
                                null
                            }
                        }
                        .collect { emit(it) }
                }
            }
            log(TAG, INFO) { "Completed scan for path: $path" }
        } catch (e: CancellationException) {
            log(TAG, INFO) { "Scan cancelled for path: $path" }
            throw e
        }
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
                    log(TAG, Logging.Priority.VERBOSE) { "Invalid regex: $query" }
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
        private val TAG = logTag("Searcher", "PathScanner")
    }
}

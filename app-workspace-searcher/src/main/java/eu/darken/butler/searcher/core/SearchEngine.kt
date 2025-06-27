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
    
    data class SearchOptions(
        val query: String,
        val startPath: APath,
        val filter: SearchFilter = SearchFilter.EMPTY,
        val searchContent: Boolean = false,
        val maxResults: Int? = null
    )
    
    data class SearchProgress(
        val currentPath: APath,
        val itemsScanned: Int,
        val resultsFound: Int
    )
    
    suspend fun search(
        options: SearchOptions,
        onProgress: ((SearchProgress) -> Unit)? = null
    ): Flow<SearchResult> = flow {
        log(TAG) { "Starting search with options: $options" }
        
        var itemsScanned = 0
        var resultsFound = 0
        
        when (val gateway = gatewaySwitch.getGateway(options.startPath)) {
            is APathGateway<*, *, *> -> {
                @Suppress("UNCHECKED_CAST")
                val typedGateway = gateway as APathGateway<APath, APathLookup<APath>, *>
                
                val walkOptions = APathGateway.WalkOptions<APath, APathLookup<APath>>(
                    onFilter = { lookup ->
                        if (!currentCoroutineContext().isActive) throw CancellationException()
                        
                        itemsScanned++
                        
                        if (itemsScanned % 100 == 0) {
                            onProgress?.invoke(
                                SearchProgress(
                                    currentPath = lookup.lookedUp,
                                    itemsScanned = itemsScanned,
                                    resultsFound = resultsFound
                                )
                            )
                        }
                        
                        filterLookup(lookup, options.filter)
                    },
                    onError = { lookup, error ->
                        log(TAG, VERBOSE) { "Error accessing ${lookup.lookedUp}: $error" }
                        true // Continue walking
                    }
                )
                
                typedGateway.walk(options.startPath, walkOptions)
            .cancellable()
            .mapNotNull { lookup ->
                if (matchesSearch(lookup, options)) {
                    resultsFound++
                    SearchResult.fromLookup(lookup, options.query)
                } else {
                    null
                }
            }
            .onEach { result ->
                if (options.maxResults != null && resultsFound >= options.maxResults) {
                    throw CancellationException("Max results reached")
                }
            }
            .collect { emit(it) }
            }
        }
            
        log(TAG) { "Search completed. Scanned: $itemsScanned, Found: $resultsFound" }
    }.flowOn(dispatcherProvider.IO)
    
    private fun filterLookup(lookup: APathLookup<*>, filter: SearchFilter): Boolean {
        // File type filter
        if (filter.fileTypes != null && lookup.fileType !in filter.fileTypes) {
            return false
        }
        
        // Size filter
        lookup.size?.let { size ->
            if (filter.minSize != null && size < filter.minSize) return false
            if (filter.maxSize != null && size > filter.maxSize) return false
        }
        
        // Modified date filter
        lookup.modifiedAt?.let { modifiedAt ->
            if (filter.modifiedAfter != null && modifiedAt.isBefore(filter.modifiedAfter)) return false
            if (filter.modifiedBefore != null && modifiedAt.isAfter(filter.modifiedBefore)) return false
        }
        
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
        options: SearchOptions
    ): Boolean = withContext(dispatcherProvider.Default) {
        val query = options.query
        val filter = options.filter
        
        // Name matching
        val name = lookup.name
        val matches = if (filter.useRegex) {
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
        } else {
            name.contains(query, ignoreCase = !filter.caseSensitive)
        }
        
        matches
    }
    
    companion object {
        private val TAG = logTag("Searcher", "Engine")
    }
}
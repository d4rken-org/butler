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
import eu.darken.butler.common.files.metadata.FileType
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.workspace.core.Workspace

class PathScanner @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val gatewaySwitch: GatewaySwitch,
    private val metadataRepo: MetadataRepo,
    private val dispatcherProvider: DispatcherProvider,
    contentMatcherFactory: ContentMatcher.Factory,
) {

    private val tag = logTag("Searcher", "Workspace", workspaceId.shortTag, "PathScanner")
    private val contentMatcher = contentMatcherFactory.create(workspaceId)

    data class PathProgress(
        val currentPath: APath<*>,
        val itemsScanned: Int,
        val resultsFound: Int,
    )

    suspend fun scan(
        path: APath<*>,
        query: SearchQuery,
        includeBinaries: Boolean,
        onProgress: (PathProgress) -> Unit
    ): Flow<SearchItem> = flow {
        log(tag, INFO) { "Scanning path: $path" }

        if (!currentCoroutineContext().isActive) {
            log(tag) { "Scan cancelled before starting" }
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
                            log(tag, Logging.Priority.VERBOSE) { "Error accessing ${lookup.lookedUp}: $error" }
                            true // Continue walking
                        }
                    )

                    typedGateway.walk(path, LookupOptions.MAX, walkOptions)
                        .cancellable()
                        .mapNotNull { lookup ->
                            // Check cancellation before expensive content matching
                            if (!currentCoroutineContext().isActive) throw CancellationException()

                            val matchResult = matchesSearch(lookup, query, includeBinaries)
                            if (matchResult != null) {
                                resultsFound++
                                val metadata = metadataRepo.extract(lookup)
                                SearchItem.fromLookup(
                                    lookup = lookup,
                                    matchedQuery = query.query,
                                    matchContext = matchResult,
                                    metadata = metadata,
                                )
                            } else {
                                null
                            }
                        }
                        .collect { emit(it) }
                }
            }
            log(tag, INFO) { "Completed scan for path: $path" }
        } catch (e: CancellationException) {
            log(tag, INFO) { "Scan cancelled for path: $path" }
            throw e
        }
    }.flowOn(dispatcherProvider.IO)

    private fun filterLookup(lookup: APathLookup<*>, filter: SearchQuery.Filter): Boolean {
        // File type filter
        if (filter.fileTypes != null && lookup.fileType !in filter.fileTypes) {
            return false
        }

        // Size filter - coerce to non-negative for safety
        val minSize = filter.minSize?.coerceAtLeast(0L)
        val maxSize = filter.maxSize?.coerceAtLeast(0L)
        if (minSize != null && lookup.size?.let { it < minSize } == true) return false
        if (maxSize != null && lookup.size?.let { it > maxSize } == true) return false

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
        searchQuery: SearchQuery,
        includeBinaries: Boolean,
    ): SearchItem.MatchContext? = withContext(dispatcherProvider.Default) {
        val query = searchQuery.query
        val filter = searchQuery.filter

        // Name matching
        val name = lookup.name
        val nameMatches = when {
            filter.useRegex -> {
                try {
                    val regex = if (filter.caseSensitive) {
                        query.toRegex()
                    } else {
                        query.toRegex(RegexOption.IGNORE_CASE)
                    }
                    regex.containsMatchIn(name)
                } catch (e: Exception) {
                    log(tag, Logging.Priority.VERBOSE) { "Invalid regex: $query" }
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

        // If filename matches, return empty match context (indicates filename match)
        if (nameMatches) {
            return@withContext SearchItem.MatchContext()
        }

        // If content search is enabled and this is a file, search content
        if (searchQuery.options.searchContent && lookup.fileType == FileType.FILE) {
            return@withContext contentMatcher.matchesContent(lookup, searchQuery, includeBinaries)
        }

        null
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): PathScanner
    }
}

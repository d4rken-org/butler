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
import eu.darken.butler.searcher.core.FilterComparator
import eu.darken.butler.searcher.core.FilterCondition
import eu.darken.butler.searcher.core.FilenameQuery
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
                                val matchedQuery = when (matchResult.matchType) {
                                    SearchItem.MatchContext.MatchType.FILENAME -> query.filenameQuery.pattern
                                    SearchItem.MatchContext.MatchType.CONTENT -> query.contentQuery.pattern
                                    SearchItem.MatchContext.MatchType.BOTH -> query.filenameQuery.pattern
                                }
                                SearchItem.fromLookup(
                                    lookup = lookup,
                                    matchedQuery = matchedQuery,
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
        // Evaluate all conditions - ALL must pass
        for (condition in filter.conditions) {
            if (!evaluateCondition(condition, lookup)) {
                return false
            }
        }
        return true
    }

    private fun evaluateCondition(condition: FilterCondition, lookup: APathLookup<*>): Boolean {
        return when (condition) {
            is FilterCondition.Size -> {
                val size = lookup.size ?: return true // Skip if size unknown
                val bytes = condition.bytes.coerceAtLeast(0L)
                when (condition.comparator) {
                    FilterComparator.GT -> size > bytes
                    FilterComparator.GTE -> size >= bytes
                    FilterComparator.LT -> size < bytes
                    FilterComparator.LTE -> size <= bytes
                    FilterComparator.EQ -> size == bytes
                }
            }
            is FilterCondition.ModifiedDate -> {
                val modifiedAt = lookup.modifiedAt ?: return true // Skip if date unknown
                when (condition.comparator) {
                    FilterComparator.GT -> modifiedAt > condition.instant
                    FilterComparator.GTE -> modifiedAt >= condition.instant
                    FilterComparator.LT -> modifiedAt < condition.instant
                    FilterComparator.LTE -> modifiedAt <= condition.instant
                    FilterComparator.EQ -> modifiedAt == condition.instant
                }
            }
        }
    }

    private suspend fun matchesSearch(
        lookup: APathLookup<*>,
        searchQuery: SearchQuery,
        includeBinaries: Boolean,
    ): SearchItem.MatchContext? = withContext(dispatcherProvider.Default) {
        val filenameQuery = searchQuery.filenameQuery
        val contentQuery = searchQuery.contentQuery
        val hasFilenameQuery = filenameQuery.isNotEmpty
        val hasContentQuery = contentQuery.isNotEmpty

        when {
            // Case 1: Only filename pattern - match filename only
            hasFilenameQuery && !hasContentQuery -> {
                if (matchesFilename(lookup.name, filenameQuery)) {
                    SearchItem.MatchContext(matchType = SearchItem.MatchContext.MatchType.FILENAME)
                } else {
                    null
                }
            }

            // Case 2: Only content pattern - match content only (for files)
            !hasFilenameQuery && hasContentQuery -> {
                if (lookup.fileType != FileType.FILE) return@withContext null
                contentMatcher.matchesContent(lookup, contentQuery, includeBinaries)
            }

            // Case 3: Both patterns - filename must match first (AND logic)
            hasFilenameQuery && hasContentQuery -> {
                // First: filename must match
                if (!matchesFilename(lookup.name, filenameQuery)) {
                    return@withContext null
                }

                // Only files can have content matches
                if (lookup.fileType != FileType.FILE) {
                    return@withContext null
                }

                // Second: content must match
                val contentMatch = contentMatcher.matchesContent(lookup, contentQuery, includeBinaries)
                contentMatch?.copy(matchType = SearchItem.MatchContext.MatchType.BOTH)
            }

            // Case 4: No patterns - shouldn't happen
            else -> null
        }
    }

    private fun matchesFilename(name: String, filenameQuery: FilenameQuery): Boolean {
        val query = filenameQuery.pattern
        if (query.isBlank()) return false

        return when {
            filenameQuery.useRegex -> {
                try {
                    val regex = if (filenameQuery.caseSensitive) {
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

            filenameQuery.wholeWord -> {
                val wordPattern = "\\b${Regex.escape(query)}\\b"
                val regex = if (filenameQuery.caseSensitive) {
                    wordPattern.toRegex()
                } else {
                    wordPattern.toRegex(RegexOption.IGNORE_CASE)
                }
                regex.containsMatchIn(name)
            }

            else -> {
                name.contains(query, ignoreCase = !filenameQuery.caseSensitive)
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): PathScanner
    }
}

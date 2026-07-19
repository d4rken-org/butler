package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.MetadataRepo
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.engine.ContentMatcher
import eu.darken.butler.searcher.core.engine.PatternMatcher
import eu.darken.butler.searcher.core.engine.SearchConfig
import eu.darken.butler.searcher.core.engine.patternOptions
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileSystemSearchBackend @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val metadataRepo: MetadataRepo,
    private val dispatcherProvider: DispatcherProvider,
    private val contentMatcher: ContentMatcher,
    private val pathPermissionCheck: PathPermissionCheck,
) : SearchBackend {

    private val tag = logTag("Searcher", "Backend", "FileSystem")

    override val priority: Int = 0

    override fun canHandle(target: SearchTarget): Boolean = target is SearchTarget.Path

    override fun supports(condition: FilterCondition): Boolean = when (condition) {
        is FilterCondition.Size, is FilterCondition.ModifiedDate, is FilterCondition.Type -> true
    }

    override fun monitorRequirements(target: SearchTarget): Flow<PathRequirements> = when (target) {
        is SearchTarget.Path -> pathPermissionCheck.monitor(target.path)
        else -> flowOf(PathRequirements())
    }

    override suspend fun scan(session: SearchBackend.ScanSession): Flow<SearchItem> {
        val target = session.target as? SearchTarget.Path ?: return emptyFlow()
        return scanPath(target.path, session)
    }

    private fun scanPath(path: APath<*>, session: SearchBackend.ScanSession): Flow<SearchItem> = flow {
        log(tag, INFO) { "[${session.workspaceId.shortTag}] Scanning path: $path" }
        val query = session.query

        var itemsScanned = 0
        var resultsFound = 0
        var errorCount = 0
        var firstErrorPath: APath<*>? = null

        fun progressSnapshot() = SearchBackend.ScanProgress(
            currentPath = path,
            itemsScanned = itemsScanned,
            resultsFound = resultsFound,
            errorCount = errorCount,
            firstErrorPath = firstErrorPath,
        )

        fun recordError(errorPath: APath<*>?) {
            errorCount++
            if (firstErrorPath == null) firstErrorPath = errorPath
        }

        try {
            val gateway = gatewaySwitch.getGateway(path)

            @Suppress("UNCHECKED_CAST")
            val typedGateway = gateway as APathGateway<APath<*>, APathLookup<APath<*>>>

            // No onFilter: pruning isn't needed (directories are always traversed, files are
            // filtered below) and its absence keeps escalated subtrees on the host-side
            // streaming walk instead of per-directory IPC.
            val walkOptions = APathGateway.WalkOptions<APath<*>, APathLookup<APath<*>>>(
                onError = { lookup, error ->
                    log(tag, VERBOSE) { "Error accessing ${lookup.lookedUp}: $error" }
                    recordError(lookup.lookedUp)
                    true // Continue walking, the failure is reported via progress
                },
                followSymlinks = query.options.followSymlinks,
            )

            typedGateway.walk(path, LOOKUP_PROJECTION, walkOptions)
                .cancellable()
                .mapNotNull { lookup ->
                    if (!currentCoroutineContext().isActive) throw CancellationException()

                    itemsScanned++
                    if (itemsScanned % SearchConfig.PROGRESS_UPDATE_INTERVAL == 0) {
                        session.onProgress(progressSnapshot())
                    }

                    if (!FilterConditionEvaluator.matchesAll(query.filter.conditions, lookup)) {
                        return@mapNotNull null
                    }

                    val matchResult = matchesSearch(lookup, query, session.includeBinaries, ::recordError)
                        ?: return@mapNotNull null

                    resultsFound++
                    val metadata = metadataRepo.extract(lookup)
                    val matchedQuery = when (matchResult.matchType) {
                        SearchItem.MatchContext.MatchType.FILENAME -> query.filenameQuery.pattern
                        SearchItem.MatchContext.MatchType.CONTENT -> query.contentQuery.pattern
                        SearchItem.MatchContext.MatchType.BOTH -> query.filenameQuery.pattern
                        SearchItem.MatchContext.MatchType.FILTER -> ""
                    }
                    SearchItem.fromLookup(
                        lookup = lookup,
                        matchedQuery = matchedQuery,
                        matchContext = matchResult,
                        metadata = metadata,
                    )
                }
                .collect { emit(it) }

            // Final flush so totals and error counts are accurate between progress intervals
            session.onProgress(progressSnapshot())
            log(tag, INFO) { "Completed scan for path: $path ($errorCount errors)" }
        } catch (e: CancellationException) {
            log(tag, INFO) { "Scan cancelled for path: $path" }
            throw e
        }
    }.flowOn(dispatcherProvider.IO)

    private suspend fun matchesSearch(
        lookup: APathLookup<*>,
        searchQuery: SearchQuery,
        includeBinaries: Boolean,
        recordError: (APath<*>?) -> Unit,
    ): SearchItem.MatchContext? {
        val filenameQuery = searchQuery.filenameQuery
        val contentQuery = searchQuery.contentQuery
        val hasFilenameQuery = filenameQuery.isNotEmpty
        val hasContentQuery = contentQuery.isNotEmpty

        suspend fun contentContext(): SearchItem.MatchContext? =
            when (val outcome = contentMatcher.matchesContent(lookup, contentQuery, includeBinaries)) {
                is ContentMatcher.Outcome.Match -> {
                    if (outcome.degraded) recordError(lookup.lookedUp)
                    outcome.context
                }
                is ContentMatcher.Outcome.NoMatch -> {
                    if (outcome.degraded) recordError(lookup.lookedUp)
                    null
                }
                is ContentMatcher.Outcome.Skipped -> null
                is ContentMatcher.Outcome.Failed -> {
                    recordError(lookup.lookedUp)
                    null
                }
            }

        return when {
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
                if (lookup.fileType != FileType.FILE) return null
                contentContext()
            }

            // Case 3: Both patterns - filename must match first (AND logic)
            hasFilenameQuery && hasContentQuery -> {
                if (!matchesFilename(lookup.name, filenameQuery)) return null
                if (lookup.fileType != FileType.FILE) return null
                contentContext()?.copy(matchType = SearchItem.MatchContext.MatchType.BOTH)
            }

            // Case 4: Filter-only - no patterns but filters exist (already applied upstream)
            searchQuery.filter.hasConditions() ->
                SearchItem.MatchContext(matchType = SearchItem.MatchContext.MatchType.FILTER)

            // Case 5: No patterns and no filters - shouldn't happen (validated upstream)
            else -> null
        }
    }

    private fun matchesFilename(name: String, filenameQuery: FilenameQuery): Boolean {
        return PatternMatcher.matches(name, filenameQuery.pattern, filenameQuery.patternOptions).isFound
    }

    companion object {
        /**
         * Query-driven projection: size/mtime feed filters, the content size-gate, display and
         * sorting; createdAt feeds the CREATED_AT sort. Ownership and permissions — the expensive
         * per-item extras that nothing in search reads — are not fetched.
         */
        internal val LOOKUP_PROJECTION = LookupOptions(
            continueOnError = true,
            fetchSize = true,
            fetchModifiedAt = true,
            fetchCreatedAt = true,
        )
    }
}

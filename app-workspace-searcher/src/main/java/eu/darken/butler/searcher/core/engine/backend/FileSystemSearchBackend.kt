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
    private val matcher: SearchItemMatcher,
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
        // Unreachable, canHandle() guards dispatch
        is SearchTarget.MediaStore -> flowOf(PathRequirements())
    }

    override suspend fun scan(session: SearchBackend.ScanSession): Flow<SearchBackend.BackendResult> {
        val target = session.target as? SearchTarget.Path ?: return emptyFlow()
        return scanPath(target.path, session)
    }

    private fun scanPath(path: APath<*>, session: SearchBackend.ScanSession): Flow<SearchBackend.BackendResult> = flow {
        log(tag, INFO) { "[${session.workspaceId.shortTag}] Scanning path: $path" }
        val query = session.query
        val progress = ScanProgressTracker(currentPath = path, onProgress = session.onProgress)

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
                    progress.recordError(lookup.lookedUp)
                    true // Continue walking, the failure is reported via progress
                },
                followSymlinks = query.options.followSymlinks,
            )

            typedGateway.walk(path, LOOKUP_PROJECTION, walkOptions)
                .cancellable()
                .mapNotNull { lookup ->
                    if (!currentCoroutineContext().isActive) throw CancellationException()

                    progress.onItemScanned()

                    // Entries that exist but couldn't be read arrive as UNKNOWN with an error
                    // (continueOnError) — count them toward the partial signal, don't match them
                    if (lookup.fileType == FileType.UNKNOWN && lookup.error != null) {
                        progress.recordError(lookup.lookedUp)
                        return@mapNotNull null
                    }

                    if (!FilterConditionEvaluator.matchesAll(query.filter.conditions, lookup)) {
                        return@mapNotNull null
                    }

                    val matchResult = matcher.match(lookup, query, session.includeBinaries, progress::recordError)
                        ?: return@mapNotNull null

                    progress.onResultFound()
                    val metadata = metadataRepo.extract(lookup)
                    SearchItem.fromLookup(
                        lookup = lookup,
                        matchedQuery = matcher.matchedQueryFor(matchResult.matchType, query),
                        matchContext = matchResult,
                        metadata = metadata,
                    )
                }
                .collect {
                    emit(SearchBackend.BackendResult(it, SearchBackend.BackendResult.RANK_FILESYSTEM))
                }

            // Final flush so totals and error counts are accurate between progress intervals
            progress.flush()
            log(tag, INFO) { "Completed scan for path: $path (${progress.errorCount} errors)" }
        } catch (e: CancellationException) {
            log(tag, INFO) { "Scan cancelled for path: $path" }
            throw e
        }
    }.flowOn(dispatcherProvider.IO)

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

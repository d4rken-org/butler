package eu.darken.butler.searcher.core.engine.backend

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.OperationCanceledException
import android.provider.MediaStore
import eu.darken.butler.common.ApiLevel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.MetadataRepo
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searches the MediaStore index instead of walking the filesystem. Fast, but only as complete
 * and fresh as the media scanner's index — that trade-off is the point of the explicit
 * [SearchTarget.MediaStore] targets (it never silently replaces a filesystem walk).
 *
 * Scoped storage: opening the indexed DATA paths requires path access. On Android 10 this is
 * covered by `requestLegacyExternalStorage` in the manifest (All-Files-Access doesn't exist
 * until 11, where it takes over; the flag is ignored there). Without path access (Android 11+
 * with All-Files-Access declined) content queries report candidates as access errors. A
 * `content://` read fallback via ContentResolver (keep `_ID`, stream through
 * `openAssetFileDescriptor` when the path open fails) was evaluated and rejected: matching
 * would succeed, but results still carry only a [LocalPath] that previews, the editor, and
 * file operations can't open either — findable but not actionable. Don't reintroduce the
 * fallback without also plumbing the content URI through [SearchItem] to those consumers.
 */
@Singleton
class MediaStoreSearchBackend @Inject constructor(
    private val contentResolver: ContentResolver,
    private val metadataRepo: MetadataRepo,
    private val dispatcherProvider: DispatcherProvider,
    private val matcher: SearchItemMatcher,
    private val pathPermissionCheck: PathPermissionCheck,
    private val apiLevel: ApiLevel,
) : SearchBackend {

    private val tag = logTag("Searcher", "Backend", "MediaStore")

    override val priority: Int = 0

    override fun canHandle(target: SearchTarget): Boolean = target is SearchTarget.MediaStore

    override fun supports(condition: FilterCondition): Boolean = when (condition) {
        is FilterCondition.Size, is FilterCondition.ModifiedDate, is FilterCondition.Type -> true
    }

    override fun monitorRequirements(target: SearchTarget): Flow<PathRequirements> = when (target) {
        is SearchTarget.MediaStore -> when {
            // Scanning an unavailable collection fails as a per-target error; showing a storage
            // setup card first would suggest granting permissions could make it work.
            !apiLevel.has(target.collection.minApiLevel) -> flowOf(PathRequirements())
            // Reading the DATA paths needs the same storage tier as browsing them
            else -> pathPermissionCheck.monitor(LocalPath.build(Environment.getExternalStorageDirectory()))
        }
        // Unreachable, canHandle() guards dispatch
        is SearchTarget.Path -> flowOf(PathRequirements())
    }

    override suspend fun scan(session: SearchBackend.ScanSession): Flow<SearchBackend.BackendResult> {
        val target = session.target as? SearchTarget.MediaStore ?: return emptyFlow()
        return scanCollection(target.collection, session)
    }

    private fun scanCollection(
        collection: SearchTarget.MediaStore.Collection,
        session: SearchBackend.ScanSession,
    ): Flow<SearchBackend.BackendResult> = flow {
        log(tag, INFO) { "[${session.workspaceId.shortTag}] Scanning collection: $collection" }
        val query = session.query
        val progress = ScanProgressTracker(currentPath = null, onProgress = session.onProgress)

        if (!apiLevel.has(collection.minApiLevel)) throw UnsupportedCollectionException(collection)
        val uri = collection.contentUri()

        coroutineScope {
            // A blocked ContentResolver.query() can't observe coroutine cancellation itself;
            // this sibling is cancelled the moment cancellation begins and trips the signal,
            // which aborts both a pending query and cursor window loads.
            val signal = CancellationSignal()
            val signalRelay = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    signal.cancel()
                }
            }

            try {
                val cursor = contentResolver.query(uri, PROJECTION, null, null, null, signal)
                    ?: throw IOException("MediaStore query returned no cursor for $uri")

                cursor.use { c ->
                    val dataIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val sizeIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val modifiedIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()

                        progress.onItemScanned()

                        val lookup = when (val outcome = MediaStoreRowDecoder.decode(
                            c.readMediaStoreRow(dataIndex, sizeIndex, modifiedIndex)
                        )) {
                            is MediaStoreRowDecoder.Outcome.Decoded -> outcome.lookup
                            // No usable DATA (e.g. redacted): expected on some devices, not an error
                            is MediaStoreRowDecoder.Outcome.Unrepresentable -> continue
                            is MediaStoreRowDecoder.Outcome.Invalid -> {
                                log(tag, VERBOSE) { "Skipping malformed row: ${outcome.reason}" }
                                progress.recordError(null)
                                continue
                            }
                        }

                        if (!FilterConditionEvaluator.matchesAll(query.filter.conditions, lookup)) continue

                        val matchResult = matcher.match(lookup, query, session.includeBinaries, progress::recordError)
                            ?: continue

                        progress.onResultFound()
                        val metadata = metadataRepo.extract(lookup)
                        val item = SearchItem.fromLookup(
                            lookup = lookup,
                            matchedQuery = matcher.matchedQueryFor(matchResult.matchType, query),
                            matchContext = matchResult,
                            metadata = metadata,
                        )
                        emit(SearchBackend.BackendResult(item, SearchBackend.BackendResult.RANK_INDEX))
                    }
                }

                // Final flush so totals and error counts are accurate between progress intervals
                progress.flush()
                log(tag, INFO) { "Completed scan for collection: $collection (${progress.errorCount} errors)" }
            } catch (e: OperationCanceledException) {
                // Only translate when OUR signal fired due to coroutine cancellation (target
                // reads CANCELLED); a provider-side abort while we're still active is a real
                // per-target error and must not cancel sibling targets.
                if (currentCoroutineContext().isActive) throw e
                throw CancellationException("MediaStore query cancelled", e)
            } catch (e: CancellationException) {
                log(tag, INFO) { "Scan cancelled for collection: $collection" }
                throw e
            } finally {
                signalRelay.cancel()
            }
        }
    }.flowOn(dispatcherProvider.IO)

    // Downloads is only reachable on API 29+: scanCollection throws UnsupportedCollectionException
    // for unavailable collections before resolving the URI
    @SuppressLint("NewApi")
    private fun SearchTarget.MediaStore.Collection.contentUri(): Uri = when (this) {
        SearchTarget.MediaStore.Collection.IMAGES ->
            if (apiLevel.has(29)) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        SearchTarget.MediaStore.Collection.VIDEO ->
            if (apiLevel.has(29)) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        SearchTarget.MediaStore.Collection.AUDIO ->
            if (apiLevel.has(29)) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        SearchTarget.MediaStore.Collection.DOWNLOADS ->
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }

    companion object {
        internal val PROJECTION = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
    }
}

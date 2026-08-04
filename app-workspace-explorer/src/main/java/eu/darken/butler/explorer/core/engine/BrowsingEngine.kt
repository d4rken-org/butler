package eu.darken.butler.explorer.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.explorer.core.BreadcrumbGenerator
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BrowsingEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val workspaceScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    homeLocationLoaderFactory: HomeLocationLoader.Factory,
    deviceLocationLoaderFactory: DeviceLocationLoader.Factory,
    trashLocationLoaderFactory: TrashLocationLoader.Factory,
    directoryLoaderFactory: DirectoryLocationLoader.Factory,
    private val breadcrumbGenerator: BreadcrumbGenerator,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "BrowsingEngine")
    private val homeLocationLoader = homeLocationLoaderFactory.create(workspaceId)
    private val deviceLocationLoader = deviceLocationLoaderFactory.create(workspaceId)
    private val trashLocationLoader = trashLocationLoaderFactory.create(workspaceId)
    private val directoryLoader = directoryLoaderFactory.create(workspaceId)

    private val targetFlow = MutableStateFlow<ExplorerNavigation.Target?>(null)
    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _location = MutableStateFlow<State>(State())
    val location: StateFlow<State> = _location.asStateFlow()

    private val pendingHints = mutableListOf<FileSystemEvent>()
    private val hintMutex = Mutex()

    data class State(
        val location: ExplorerLocation? = null,
        val error: Throwable? = null,
        val breadcrumbs: List<ExplorerBreadcrumb>? = null,
        /** A [refresh] of the location that is already displayed is running, as opposed to a load for a new target. */
        val isRefreshing: Boolean = false,
        /**
         * Counts [refresh] calls. This is a conflating [StateFlow] and a refresh of unchanged
         * content can start and finish between two collector resumptions, in which case
         * [isRefreshing] is never observed as true and the refresh would pass entirely unnoticed.
         * A counter survives that: its new value is still there in whichever state does arrive.
         */
        val refreshId: Int = 0,
    )

    /** A loader run, tagged with what started it: a new target, or a [refresh] of the one already displayed. */
    private data class Load(
        val location: ExplorerLocation,
        val isRefresh: Boolean,
    )

    init {
        targetFlow
            .onEach { log(tag) { "New target: $it" } }
            .filterNotNull()
            .onEach { target ->
                // Clear stale hints when navigating away
                hintMutex.withLock {
                    if (pendingHints.isNotEmpty()) {
                        log(tag) { "Navigation changed, discarding ${pendingHints.size} stale hints" }
                        pendingHints.clear()
                    }
                }
            }
            .flatMapLatest { target ->
                refreshTrigger
                    .map { true }
                    .onStart { emit(false) }
                    .onEach { isRefresh ->
                        log(tag) { "Loading target (refresh=$isRefresh): $target" }
                        hintMutex.withLock { pendingHints.clear() }
                    }
                    .flatMapLatest { isRefresh ->
                        when (target) {
                            is ExplorerNavigation.Target.Home -> homeLocationLoader.loadHome()
                            is ExplorerNavigation.Target.Device -> deviceLocationLoader.loadDevice()
                            is ExplorerNavigation.Target.Trash.Root -> trashLocationLoader.loadRoot()
                            is ExplorerNavigation.Target.Trash.Nested -> trashLocationLoader.loadNested(
                                target.parentItem,
                                target.relativePath,
                            )
                            is ExplorerNavigation.Target.Directory -> directoryLoader.loadDirectory(target.path)
                        }
                            .flowOn(dispatcherProvider.IO)
                            .map { Load(location = it, isRefresh = isRefresh) }
                            .catch {
                                log(tag, ERROR) { "Browsing failed on $target\n${it.asLog()}" }
                                _location.value = _location.value.copy(
                                    error = it,
                                    location = null,
                                    isRefreshing = false,
                                )
                            }
                    }
            }
            .onEach { (location, isRefresh) ->
                val previousLocation = _location.value.location
                val pathChanged = location.locationId != previousLocation?.locationId

                val breadcrumbs = if (pathChanged) {
                    breadcrumbGenerator.getBreadcrumbs(location).also {
                        log(tag, INFO) { "Breadcrumbs updated: $it" }
                    }
                } else {
                    _location.value.breadcrumbs ?: emptyList()
                }

                val published = previousLocation
                    ?.takeIf { isRefresh && !pathChanged }
                    ?.let { location.retainContentFrom(it) }
                    ?: location

                _location.value = State(
                    location = published,
                    breadcrumbs = breadcrumbs,
                    isRefreshing = isRefresh && location.isLoading,
                    refreshId = _location.value.refreshId,
                )

                // When loading completes, process queued hints
                if (!location.isLoading) {
                    hintMutex.withLock {
                        if (pendingHints.isNotEmpty()) {
                            log(tag) { "Loading complete, processing ${pendingHints.size} queued hints" }
                            pendingHints.forEach { event ->
                                val current = _location.value.location as? ExplorerLocation.Directory ?: return@forEach
                                val updated = applyIncrementalUpdate(current, event)
                                _location.value = _location.value.copy(location = updated)
                            }
                            pendingHints.clear()
                        }
                    }
                }
            }
            .launchIn(workspaceScope)
    }

    fun setTarget(target: ExplorerNavigation.Target) {
        log(tag, INFO) { "setTarget(): $target" }
        // Cancelling a refresh by navigating elsewhere kills the loader without an emission, so the
        // flag has to be dropped here - the new target's own load may take a while to report in.
        _location.value = _location.value.copy(isRefreshing = false)
        targetFlow.value = target
    }

    suspend fun hint(event: FileSystemEvent) = hintMutex.withLock {
        log(tag) { "hint(): $event" }
        val current = _location.value.location as? ExplorerLocation.Directory ?: return@withLock

        if (current.isLoading) {
            log(tag) { "hint(): Queueing event (loading in progress)" }
            pendingHints.add(event)
        } else {
            log(tag) { "hint(): Applying incremental update" }
            val updated = applyIncrementalUpdate(current, event)
            _location.value = _location.value.copy(location = updated)
        }
    }

    private suspend fun applyIncrementalUpdate(
        current: ExplorerLocation.Directory,
        event: FileSystemEvent
    ): ExplorerLocation.Directory {
        val currentItems = current.items ?: return current

        val newItems = when (event) {
            is FileSystemEvent.Added -> {
                // Only apply if event affects current directory
                val affectedPaths = event.paths.filter { it.lookedUp.parent == current.path }
                if (affectedPaths.isEmpty()) {
                    log(tag) { "applyIncrementalUpdate(): Event doesn't affect current directory" }
                    return current
                }
                log(tag) { "applyIncrementalUpdate(): Adding ${affectedPaths.size} paths" }
                val added = directoryLoader.classifyLookups(affectedPaths)
                (currentItems + added).distinctBy { it.path.path }
            }
            is FileSystemEvent.Removed -> {
                // Only apply if event affects current directory
                val affectedPaths = event.paths.filter { it.lookedUp.parent == current.path }
                if (affectedPaths.isEmpty()) {
                    log(tag) { "applyIncrementalUpdate(): Event doesn't affect current directory" }
                    return current
                }

                log(tag) { "applyIncrementalUpdate(): Removing ${affectedPaths.size} paths" }
                val removedPaths = affectedPaths.map { it.lookedUp.path }.toSet()
                currentItems.filter { it.path.path !in removedPaths }
            }
            is FileSystemEvent.Modified -> {
                // Only apply if event affects current directory
                val affectedPaths = event.paths.filter { it.lookedUp.parent == current.path }
                if (affectedPaths.isEmpty()) {
                    log(tag) { "applyIncrementalUpdate(): Event doesn't affect current directory" }
                    return current
                }

                log(tag) { "applyIncrementalUpdate(): Modifying ${affectedPaths.size} paths" }
                val updatedItems = directoryLoader.classifyLookups(affectedPaths).associateBy { it.path.path }
                currentItems.map { item -> updatedItems[item.path.path] ?: item }
            }
        }

        // Keep the stat-bar counts in sync with the items so it doesn't show a stale
        // "Empty folder" / wrong count after an operation completes in the current directory.
        return current.copy(
            items = newItems,
            info = current.info?.withCountsFrom(newItems),
        )
    }

    suspend fun refresh() {
        log(tag, INFO) { "refresh()" }
        _location.value = _location.value.let { it.copy(refreshId = it.refreshId + 1) }
        refreshTrigger.emit(Unit)
    }

    fun release() {
        log(tag, INFO) { "release()" }
        workspaceScope.cancel()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            workspaceScope: CoroutineScope,
        ): BrowsingEngine
    }
}

/**
 * Keeps the content that is already on screen while the same location reloads.
 *
 * A refresh restarts the loader, which first emits a location with no items at all and then a peek
 * listing whose items carry different ids than the finished lookups. Publishing those tears the
 * list down to skeletons and rebuilds every row twice for the length of the load - and the skeleton
 * branch renders on its own list state, so the scroll position visibly snaps to the top and back.
 * The previous items and info are therefore held until the reload has produced a listing of its
 * own. Progress is not held: it still reports that a load is running.
 *
 * Only called for a refresh of the location that is already displayed, so the receiver and
 * [previous] are the same variant in practice - a mismatch just skips the carry-over.
 */
internal fun ExplorerLocation.retainContentFrom(previous: ExplorerLocation): ExplorerLocation {
    if (!isLoading || previous.items == null || hasOwnListing) return this
    return when (this) {
        is ExplorerLocation.Home -> (previous as? ExplorerLocation.Home)
            ?.let { copy(items = it.items, info = it.info) }

        is ExplorerLocation.Device -> (previous as? ExplorerLocation.Device)
            ?.let { copy(items = it.items, info = it.info) }

        is ExplorerLocation.Directory -> (previous as? ExplorerLocation.Directory)
            ?.let { copy(items = it.items, info = it.info) }

        is ExplorerLocation.Trash.Root -> (previous as? ExplorerLocation.Trash.Root)
            ?.let { copy(items = it.items, info = it.info) }

        is ExplorerLocation.Trash.Nested -> (previous as? ExplorerLocation.Trash.Nested)
            ?.let { copy(items = it.items, info = it.info) }
    } ?: this
}

/**
 * Whether this emission carries a listing of its own, as opposed to nothing yet or the loader's
 * peek stage. An empty listing counts - a directory whose contents were deleted has to be able to
 * replace the retained items.
 */
private val ExplorerLocation.hasOwnListing: Boolean
    get() = items?.none { it is ExplorerItem.Peek } == true

/**
 * Recomputes the directory file/folder counts from the given items so the stat-bar stays in sync
 * after an incremental update (e.g. a paste/copy/move/delete into the current directory), instead
 * of showing a stale "Empty folder" / wrong count until the user navigates away and back.
 */
internal fun ExplorerLocation.Directory.Info.withCountsFrom(
    items: List<ExplorerItem.Path>,
): ExplorerLocation.Directory.Info {
    var fileCount = 0
    var directoryCount = 0
    var totalSize = 0L
    items.forEach { item ->
        when (item) {
            is ExplorerItem.Directory -> directoryCount++
            is ExplorerItem.File -> {
                fileCount++
                totalSize += item.lookup.size ?: 0L
            }
            else -> Unit
        }
    }
    return copy(
        fileCount = fileCount,
        directoryCount = directoryCount,
        totalSize = if (totalSize > 0) totalSize else null,
    )
}

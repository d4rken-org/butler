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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BrowsingEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val workspaceScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    homeLocationLoaderFactory: HomeLocationLoader.Factory,
    deviceLocationLoaderFactory: DeviceLocationLoader.Factory,
    networkLocationLoaderFactory: NetworkLocationLoader.Factory,
    trashLocationLoaderFactory: TrashLocationLoader.Factory,
    directoryLoaderFactory: DirectoryLocationLoader.Factory,
    private val breadcrumbGenerator: BreadcrumbGenerator,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "BrowsingEngine")
    private val homeLocationLoader = homeLocationLoaderFactory.create(workspaceId)
    private val deviceLocationLoader = deviceLocationLoaderFactory.create(workspaceId)
    private val networkLocationLoader = networkLocationLoaderFactory.create(workspaceId)
    private val trashLocationLoader = trashLocationLoaderFactory.create(workspaceId)
    private val directoryLoader = directoryLoaderFactory.create(workspaceId)

    private val sessionFlow = MutableStateFlow<Session?>(null)
    private val _location = MutableStateFlow<State>(State())
    val location: StateFlow<State> = _location.asStateFlow()

    private val pendingHints = mutableListOf<FileSystemEvent>()
    private val hintMutex = Mutex()

    /**
     * Fences emissions of a load against a [cancelLoad] that happened after they were produced.
     * [flatMapLatest] buffers, so cancelling the loader coroutine does not stop what it already
     * emitted from arriving - and a gateway call that only dies at its next checkpoint can even
     * produce new emissions afterwards. Every load carries the value this counter had when its
     * request was accepted; a bump makes all of them stale at once. Sampling it when the loader
     * starts instead would let a command that waited in its session's queue while a cancel bumped
     * the counter adopt the new value and publish after all.
     *
     * Only [cancelLoad] bumps it. Ordinary load succession is already handled by [flatMapLatest],
     * and a late emission of the load before it must still be published: if it is that load's
     * settle, it is the restore point a following cancel falls back to.
     */
    private val generation = AtomicInteger(0)

    /**
     * Identifies a load, so an emission can be told apart from the load that is running now.
     * Assigned when a load is accepted ([setTarget], [refresh]) and carried with the request, never
     * read at loader start: a command that sits in its session's queue for a while must keep the id
     * of the request it belongs to instead of adopting a newer one.
     */
    private val loadIds = AtomicInteger(NO_LOAD_ID)

    /** The id of the load the engine is waiting for, i.e. the one [currentLoadKind] describes. */
    @Volatile private var currentLoadId: Int = NO_LOAD_ID

    /** What the running load was started by, so a [cancelLoad] knows which outcome it owes. */
    @Volatile private var currentLoadKind: LoadKind? = null

    /** The last content that settled, i.e. what a cancel falls back to. */
    @Volatile private var lastStable: StableSnapshot? = null

    /**
     * The target the engine is armed with, and whether arming it also started a load.
     *
     * Equality is effectively identity because of [commands], so a session is never deduplicated by
     * the [StateFlow] - [setTarget] compares target and start action explicitly instead.
     */
    private data class Session(
        val target: ExplorerNavigation.Target,
        val startAction: StartAction,
        /** The id of the [LoadCommand.Initial] this session starts with, unused for [StartAction.HOLD]. */
        val loadId: Int,
        /** The generation that [LoadCommand.Initial] belongs to, unused for [StartAction.HOLD]. */
        val generation: Int,
        /**
         * Per-session queue. A shared flow would drop a command issued while the session handoff is
         * still waiting for a cancelled loader to wind down - there is no subscriber in that window.
         */
        val commands: Channel<LoadCommand> = Channel(Channel.BUFFERED),
    )

    /** [HOLD] arms a target without loading it - what a cancel leaves behind, so a later load works. */
    private enum class StartAction { LOAD, HOLD }

    /**
     * A load request, with the ids it was accepted under. They are carried instead of sampled when
     * the command runs: a command can wait in its session's queue while a cancel bumps the
     * generation behind it, and it must not adopt the value that cancel installed.
     */
    private sealed interface LoadCommand {
        data class Initial(val loadId: Int, val generation: Int) : LoadCommand
        data class Refresh(val loadId: Int, val generation: Int) : LoadCommand
        data object Cancel : LoadCommand
    }

    private enum class LoadKind { NAVIGATION, REFRESH }

    private data class StableSnapshot(
        val target: ExplorerNavigation.Target,
        val state: State,
    )

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
        /** The target [location] belongs to, so a consumer can pair it with its own navigation state. */
        val target: ExplorerNavigation.Target? = null,
    )

    /** What a [cancelLoad] did, i.e. what the caller still has to roll back itself. */
    sealed interface CancelResult {
        /** Nothing was running, nothing was published. */
        data object NoLoadRunning : CancelResult

        /** A refresh was stopped, the content it was refreshing is back on screen. */
        data object RefreshCancelled : CancelResult

        /** A load for a new target was stopped, [target] is the content that is displayed again. */
        data class NavigationRestored(val target: ExplorerNavigation.Target) : CancelResult

        /** A load for [target] was stopped and there was nothing to fall back to. */
        data class NothingToRestore(val target: ExplorerNavigation.Target) : CancelResult
    }

    /** A loader run, tagged with what started it: a new target, or a [refresh] of the one already displayed. */
    private data class Load(
        val location: ExplorerLocation,
        val isRefresh: Boolean,
        val target: ExplorerNavigation.Target,
        val generation: Int,
        val loadId: Int,
    )

    init {
        sessionFlow
            .onEach { log(tag) { "New session: $it" } }
            .filterNotNull()
            .onEach {
                // Clear stale hints when navigating away
                hintMutex.withLock {
                    if (pendingHints.isNotEmpty()) {
                        log(tag) { "Navigation changed, discarding ${pendingHints.size} stale hints" }
                        pendingHints.clear()
                    }
                }
            }
            .flatMapLatest { session ->
                // The command queue outlives each load, so a cancelled session stays armed: a later
                // refresh or navigation starts a new loader without a new subscription.
                session.commands.receiveAsFlow()
                    .onStart {
                        emit(
                            when (session.startAction) {
                                StartAction.LOAD -> LoadCommand.Initial(session.loadId, session.generation)
                                StartAction.HOLD -> LoadCommand.Cancel
                            }
                        )
                    }
                    .onEach { command ->
                        if (command == LoadCommand.Cancel) return@onEach
                        log(tag) { "Loading target ($command): ${session.target}" }
                        hintMutex.withLock { pendingHints.clear() }
                    }
                    .flatMapLatest { command ->
                        when (command) {
                            // Cancels the running loader, the session subscription stays alive.
                            LoadCommand.Cancel -> emptyFlow()
                            is LoadCommand.Initial -> startLoad(
                                target = session.target,
                                loadId = command.loadId,
                                loadGeneration = command.generation,
                                isRefresh = false,
                            )
                            is LoadCommand.Refresh -> startLoad(
                                target = session.target,
                                loadId = command.loadId,
                                loadGeneration = command.generation,
                                isRefresh = true,
                            )
                        }
                    }
            }
            .onEach { load ->
                if (load.generation != generation.get()) {
                    log(tag) { "Dropping emission of a cancelled load: ${load.location.locationId}" }
                    return@onEach
                }
                val location = load.location
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
                    ?.takeIf { load.isRefresh && !pathChanged }
                    ?.let { location.retainContentFrom(it) }
                    ?: location

                _location.value = State(
                    location = published,
                    breadcrumbs = breadcrumbs,
                    isRefreshing = load.isRefresh && location.isLoading,
                    refreshId = _location.value.refreshId,
                    target = load.target,
                )

                // When loading completes, process queued hints
                if (!location.isLoading) {
                    // Only the load that is actually being waited for may report itself as done: a
                    // late settle of the previous target would otherwise disarm the cancel and the
                    // refresh guard for the load that is running now.
                    if (load.loadId == currentLoadId) currentLoadKind = null
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
                    rememberStable()
                }
            }
            .launchIn(workspaceScope)
    }

    private fun startLoad(
        target: ExplorerNavigation.Target,
        loadId: Int,
        loadGeneration: Int,
        isRefresh: Boolean,
    ): Flow<Load> {
        return loaderFor(target, isRefresh)
            .flowOn(dispatcherProvider.IO)
            .map {
                Load(
                    location = it,
                    isRefresh = isRefresh,
                    target = target,
                    generation = loadGeneration,
                    loadId = loadId,
                )
            }
            .catch {
                log(tag, ERROR) { "Browsing failed on $target\n${it.asLog()}" }
                if (loadGeneration != generation.get()) {
                    log(tag, WARN) { "Dropping error of a cancelled load" }
                    return@catch
                }
                if (loadId == currentLoadId) currentLoadKind = null
                _location.value = _location.value.copy(
                    error = it,
                    location = null,
                    isRefreshing = false,
                )
            }
    }

    /** [isRefresh] is what the user asking for fresh data looks like down here, see the Network loader. */
    private fun loaderFor(target: ExplorerNavigation.Target, isRefresh: Boolean): Flow<ExplorerLocation> = when (target) {
        is ExplorerNavigation.Target.Home -> homeLocationLoader.loadHome()
        is ExplorerNavigation.Target.Device -> deviceLocationLoader.loadDevice()
        is ExplorerNavigation.Target.Network -> networkLocationLoader.loadNetwork(force = isRefresh)
        is ExplorerNavigation.Target.Trash.Root -> trashLocationLoader.loadRoot()
        is ExplorerNavigation.Target.Trash.Nested -> trashLocationLoader.loadNested(
            target.parentItem,
            target.relativePath,
        )
        is ExplorerNavigation.Target.Directory -> directoryLoader.loadDirectory(target.path)
    }

    /** Remembers the currently published content as the restore point for a [cancelLoad]. */
    private fun rememberStable() {
        val state = _location.value
        val location = state.location ?: return
        val target = state.target ?: return
        if (location.isLoading || state.error != null) return
        lastStable = StableSnapshot(target, state.copy(isRefreshing = false))
    }

    fun setTarget(target: ExplorerNavigation.Target) {
        log(tag, INFO) { "setTarget(): $target" }
        val current = sessionFlow.value
        if (current != null && current.target == target && current.startAction == StartAction.LOAD) {
            // Nothing would start - claiming a load anyway would make a later cancel restore stale
            // content over the target that is actually displayed.
            log(tag) { "setTarget(): Already loading this target, nothing to do" }
            return
        }
        val loadId = loadIds.incrementAndGet()
        val acceptedGeneration = generation.get()
        currentLoadId = loadId
        currentLoadKind = LoadKind.NAVIGATION
        // Cancelling a refresh by navigating elsewhere kills the loader without an emission, so the
        // flag has to be dropped here - the new target's own load may take a while to report in.
        _location.value = _location.value.copy(isRefreshing = false)
        sessionFlow.value = Session(
            target = target,
            startAction = StartAction.LOAD,
            loadId = loadId,
            generation = acceptedGeneration,
        )
    }

    /**
     * Stops the running load and puts back what the user was looking at.
     *
     * The loader coroutine is cancelled cooperatively, so a single long gateway call (SAF/root/ADB
     * `listFiles`) only ends at its next checkpoint. Its results can never reach the UI though: the
     * generation is bumped before anything is published, which makes every emission of the
     * cancelled load - buffered or still to come - stale by construction.
     */
    suspend fun cancelLoad(): CancelResult {
        log(tag, INFO) { "cancelLoad()" }
        val kind = currentLoadKind
        val session = sessionFlow.value
        if (kind == null || session == null) {
            log(tag) { "cancelLoad(): Nothing is loading" }
            return CancelResult.NoLoadRunning
        }
        val stable = lastStable

        if (kind == LoadKind.REFRESH) {
            // Not just stripping the progress: once the refresh published a listing of its own,
            // retainContentFrom stops protecting the old one, so only the snapshot brings back the
            // complete pre-refresh content instead of freezing a half-refreshed one.
            if (stable != null && stable.target == session.target) {
                log(tag, INFO) { "cancelLoad(): Restoring the refreshed content of ${session.target}" }
                generation.incrementAndGet()
                session.commands.send(LoadCommand.Cancel)
                dropPendingHints()
                currentLoadKind = null
                _location.value = stable.state.copy(refreshId = _location.value.refreshId)
                return CancelResult.RefreshCancelled
            }
            val current = _location.value
            if (current.location != null) {
                log(tag, INFO) { "cancelLoad(): No snapshot, stopping the refresh in place" }
                generation.incrementAndGet()
                session.commands.send(LoadCommand.Cancel)
                dropPendingHints()
                currentLoadKind = null
                _location.value = current.copy(
                    location = current.location.withoutProgress(),
                    isRefreshing = false,
                )
                return CancelResult.RefreshCancelled
            }
            log(tag, WARN) { "cancelLoad(): Refresh without any content, treating it as a navigation" }
        }

        generation.incrementAndGet()
        dropPendingHints()
        currentLoadKind = null
        return if (stable != null) {
            log(tag, INFO) { "cancelLoad(): Restoring ${stable.target}" }
            // Re-arming the session cancels the loader through the outer flatMapLatest.
            sessionFlow.value = Session(stable.target, StartAction.HOLD, NO_LOAD_ID, generation.get())
            _location.value = stable.state.copy(refreshId = _location.value.refreshId)
            CancelResult.NavigationRestored(stable.target)
        } else {
            log(tag, INFO) { "cancelLoad(): Nothing to restore for ${session.target}" }
            sessionFlow.value = Session(session.target, StartAction.HOLD, NO_LOAD_ID, generation.get())
            _location.value = State(
                location = null,
                breadcrumbs = _location.value.breadcrumbs,
                error = BrowsingAbortedException(session.target),
                isRefreshing = false,
                refreshId = _location.value.refreshId,
            )
            CancelResult.NothingToRestore(session.target)
        }
    }

    private suspend fun dropPendingHints() = hintMutex.withLock {
        if (pendingHints.isEmpty()) return@withLock
        log(tag) { "Load cancelled, discarding ${pendingHints.size} queued hints" }
        pendingHints.clear()
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
            // Otherwise a cancel would restore the listing from before this update.
            rememberStable()
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
        if (currentLoadKind == LoadKind.NAVIGATION) {
            // Reclassifying the running navigation as a refresh would freeze its partial content as
            // the state a cancel restores, instead of the location the user came from.
            log(tag, WARN) { "refresh(): A new target is still loading, ignoring" }
            return
        }
        val session = sessionFlow.value
        if (session == null) {
            log(tag, WARN) { "refresh(): No target armed, ignoring" }
            return
        }
        val loadId = loadIds.incrementAndGet()
        val acceptedGeneration = generation.get()
        currentLoadId = loadId
        currentLoadKind = LoadKind.REFRESH
        _location.value = _location.value.let { it.copy(refreshId = it.refreshId + 1) }
        session.commands.send(LoadCommand.Refresh(loadId, acceptedGeneration))
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

    companion object {
        /** Id of no load at all: what a session that only arms a target carries. */
        private const val NO_LOAD_ID = 0
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

        is ExplorerLocation.Network -> (previous as? ExplorerLocation.Network)
            ?.let { copy(items = it.items, info = it.info) }

        is ExplorerLocation.Directory -> (previous as? ExplorerLocation.Directory)
            ?.let { copy(items = it.items, info = it.info) }

        is ExplorerLocation.Trash.Root -> (previous as? ExplorerLocation.Trash.Root)
            ?.let { copy(items = it.items, info = it.info) }

        is ExplorerLocation.Trash.Nested -> (previous as? ExplorerLocation.Trash.Nested)
            ?.let { copy(items = it.items, info = it.info) }
    } ?: this
}

/** The same location with its load stopped: what is on screen stays, the progress reporting ends. */
internal fun ExplorerLocation.withoutProgress(): ExplorerLocation = when (this) {
    is ExplorerLocation.Home -> copy(progress = null)
    is ExplorerLocation.Device -> copy(progress = null)
    is ExplorerLocation.Network -> copy(progress = null)
    is ExplorerLocation.Directory -> copy(progress = null)
    is ExplorerLocation.Trash.Root -> copy(progress = null)
    is ExplorerLocation.Trash.Nested -> copy(progress = null)
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

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
import eu.darken.butler.explorer.core.filesystem.FileSystemEvent
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
                    .onStart { emit(Unit) }
                    .onEach { log(tag) { "Loading/refreshing target: $target" } }
                    .flatMapLatest {
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
                            .catch {
                                log(tag, ERROR) { "Browsing failed on $target\n${it.asLog()}" }
                                _location.value = _location.value.copy(
                                    error = it,
                                    location = null,
                                )
                            }
                    }
            }
            .onEach { location ->
                val previousLocation = _location.value.location
                val pathChanged = location.locationId != previousLocation?.locationId

                val breadcrumbs = if (pathChanged) {
                    breadcrumbGenerator.getBreadcrumbs(location).also {
                        log(tag, INFO) { "Breadcrumbs updated: $it" }
                    }
                } else {
                    _location.value.breadcrumbs ?: emptyList()
                }

                _location.value = State(
                    location = location,
                    breadcrumbs = breadcrumbs,
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

        return when (event) {
            is FileSystemEvent.Added -> {
                // Only apply if event affects current directory
                val affectedPaths = event.paths.filter { it.lookedUp.parent == current.path }
                if (affectedPaths.isEmpty()) {
                    log(tag) { "applyIncrementalUpdate(): Event doesn't affect current directory" }
                    return current
                }
                log(tag) { "applyIncrementalUpdate(): Adding ${affectedPaths.size} paths" }
                val newItems = directoryLoader.classifyLookups(affectedPaths)
                current.copy(items = (currentItems + newItems).distinctBy { it.path.path })
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
                current.copy(items = currentItems.filter { it.path.path !in removedPaths })
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
                current.copy(items = currentItems.map { item ->
                    updatedItems[item.path.path] ?: item
                })
            }
        }
    }

    suspend fun refresh() {
        log(tag, INFO) { "refresh()" }
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
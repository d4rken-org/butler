package eu.darken.butler.explorer.core.engine

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BrowsingEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val homeLocationLoader: HomeLocationLoader,
    private val deviceLocationLoader: DeviceLocationLoader,
    private val directoryLoaderFactory: DirectoryLocationLoader.Factory,
    private val gatewaySwitch: GatewaySwitch,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "BrowsingEngine")
    private val directoryLoader = directoryLoaderFactory.create(workspaceId)

    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    private val targetFlow = MutableStateFlow<ExplorerNavigation.Target?>(null)
    private val _location = MutableStateFlow<ExplorerLocation?>(null)
    val location: StateFlow<ExplorerLocation?> = _location.asStateFlow()

    private val pendingHints = mutableListOf<FileSystemEvent>()
    private val hintMutex = Mutex()

    init {
        targetFlow
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
                when (target) {
                    is ExplorerNavigation.Target.Home -> homeLocationLoader.loadHome()
                    is ExplorerNavigation.Target.Device -> deviceLocationLoader.loadDevice()
                    is ExplorerNavigation.Target.Directory -> directoryLoader.loadDirectory(target.path)
                }.flowOn(dispatcherProvider.IO)
            }
            .onEach { location ->
                _location.value = location

                // When loading completes, process queued hints
                if (!location.isLoading) {
                    hintMutex.withLock {
                        if (pendingHints.isNotEmpty()) {
                            log(tag) { "Loading complete, processing ${pendingHints.size} queued hints" }
                            pendingHints.forEach { event ->
                                val current = _location.value as? ExplorerLocation.Directory ?: return@forEach
                                _location.value = applyIncrementalUpdate(current, event)
                            }
                            pendingHints.clear()
                        }
                    }
                }
            }
            .launchIn(scope)
    }

    fun setTarget(target: ExplorerNavigation.Target) {
        log(tag, INFO) { "setTarget(): $target" }
        targetFlow.value = target
    }

    suspend fun hint(event: FileSystemEvent) = hintMutex.withLock {
        log(tag) { "hint(): $event" }
        val current = _location.value as? ExplorerLocation.Directory ?: return@withLock

        if (current.isLoading) {
            log(tag) { "hint(): Queueing event (loading in progress)" }
            pendingHints.add(event)
        } else {
            log(tag) { "hint(): Applying incremental update" }
            _location.value = applyIncrementalUpdate(current, event)
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
                val newLookups = affectedPaths.map { gatewaySwitch.lookup(it.lookedUp) }
                val newItems = directoryLoader.classifyLookups(newLookups)
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
                val updatedLookups = affectedPaths
                    .map { gatewaySwitch.lookup(it.lookedUp) }
                val updatedItems = directoryLoader.classifyLookups(updatedLookups).associateBy { it.path.path }
                current.copy(items = currentItems.map { item ->
                    updatedItems[item.path.path] ?: item
                })
            }
        }
    }

    fun refresh() {
        log(tag, INFO) { "refresh()" }
        targetFlow.value?.let { targetFlow.value = it }
    }

    fun release() {
        log(tag, INFO) { "release()" }
        scope.cancel()
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): BrowsingEngine
    }
}
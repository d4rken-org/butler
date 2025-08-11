package eu.darken.butler.explorer.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.ExplorerEngine
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize


class ExplorerWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Arguments?,
    dispatcherProvider: DispatcherProvider,
    private val engine: ExplorerEngine,
    private val breadcrumbGenerator: BreadcrumbGenerator,
) : Workspace {

    private val tag = logTag("Explorer", "Workspace", id.shortTag)

    private val scope = CoroutineScope(dispatcherProvider.IO)

    override val type: Workspace.Type = Workspace.Type.EXPLORER

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = "Explorer ${id.shortTag}".toCaString(),
        )
    )

    val current = MutableStateFlow<State>(State())

    private val navigationRequests = MutableSharedFlow<ExplorerNavigation>(replay = 1)

    data class State(
        val currentTarget: ExplorerNavigation.Target? = null,
        val currentLocation: ExplorerLocation? = null,
        val currentBreadcrumbs: List<ExplorerBreadcrumb>? = null,
        val navigationHistory: List<ExplorerNavigation.Target> = emptyList(),
        val historyIndex: Int = 0,
        val isLoading: Boolean = false,
        val isLoadingExtended: Boolean = false,
        val error: Throwable? = null,
        val progress: Progress.Data? = null,
    ) {
        val canGoBack: Boolean get() = historyIndex > 0
        val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
    }

    init {
        // Set up navigation flow processing
        navigationRequests
            .onEach { log(tag, INFO) { "New navigation request: $it" } }
            .flatMapLatest { request ->
                flow<Unit> {
                    try {
                        processNavigationRequest(request)
                        emit(Unit)
                    } catch (e: CancellationException) {
                        log(tag, INFO) { "Navigation cancelled" }
                        current.value = current.value.copy(
                            isLoading = false,
                            isLoadingExtended = false,
                        )
                        throw e  // Re-throw to maintain cancellation semantics
                    } catch (e: Exception) {
                        // Handle all other exceptions here to prevent flow termination
                        log(tag, ERROR) { "Navigation failed: $e" }
                        current.value = current.value.copy(
                            error = e,
                            isLoading = false,
                            isLoadingExtended = false,
                        )
                        emit(Unit)
                    }
                }
            }
            .launchIn(scope)

        // Load initial location
        scope.launch {
            log(tag, INFO) { "Loading initial data... ($arguments)" }
            try {
                current.value = current.value.copy(isLoading = true)

                val startPath = arguments?.startPath
                if (startPath != null) {
                    navigationRequests.emit(ExplorerNavigation.Target.Directory(startPath))
                } else {
                    navigationRequests.emit(ExplorerNavigation.Target.Home)
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to initialize: $e" }
                current.value = current.value.copy(
                    isLoading = false,
                    error = e
                )
            }
        }
    }

    private suspend fun processNavigationRequest(target: ExplorerNavigation) {
        log(tag, INFO) { "Processing navigation target: $target" }

        when (target) {
            is ExplorerNavigation.Target -> {
                navigateToLocationInternal(target, addToHistory = true)
            }

            is ExplorerNavigation.Back -> {
                val currentHistory = current.value.navigationHistory
                val currentIndex = current.value.historyIndex

                if (currentIndex > 0) {
                    val targetLocation = currentHistory[currentIndex - 1]
                    navigateToLocationInternal(targetLocation, addToHistory = false)
                    current.value = current.value.copy(historyIndex = currentIndex - 1)
                }
            }
            is ExplorerNavigation.Forward -> {
                val currentHistory = current.value.navigationHistory
                val currentIndex = current.value.historyIndex

                if (currentIndex < currentHistory.size - 1) {
                    val targetLocation = currentHistory[currentIndex + 1]
                    navigateToLocationInternal(targetLocation, addToHistory = false)
                    current.value = current.value.copy(historyIndex = currentIndex + 1)
                }
            }
            is ExplorerNavigation.Refresh -> {
                current.value.currentTarget?.let { target ->
                    navigateToLocationInternal(target, addToHistory = false)
                }
            }
            is ExplorerNavigation.Cancel -> {
                // Just reset the loading state, flatMapLatest will have already cancelled the previous operation
                log(tag, INFO) { "Navigation cancel request processed" }
                current.value = current.value.copy(
                    isLoading = false,
                    isLoadingExtended = false,
                )
            }
        }
    }

    fun navigate(request: ExplorerNavigation) {
        log(tag) { "navigate(): $request" }
        scope.launch {
            log(tag) { "navigate(): Launching $request" }
            navigationRequests.emit(request)
            log(tag) { "navigate(): Submitted $request" }
        }
    }

    private suspend fun navigateToLocationInternal(target: ExplorerNavigation.Target, addToHistory: Boolean) {
        try {
            log(tag, INFO) { "Navigating to: $target" }
            current.value = current.value.copy(
                currentTarget = target,
                isLoading = true,
                error = null
            )

            // Load items for Directory locations
            val location = when (target) {
                is ExplorerNavigation.Target.Home -> engine.getHomeEntry()
                is ExplorerNavigation.Target.Device -> engine.getDevice()
                is ExplorerNavigation.Target.Directory -> {
                    val items = engine.getContent(target.path)

                    val newLocation = ExplorerLocation.Directory(
                        path = target.path,
                        items = items,
                    )

                    // Load extended data in background
                    scope.launch {
                        loadExtendedData(target.path)
                    }

                    newLocation
                }
            }

            val newHistory = if (addToHistory) {
                val currentHistory = current.value.navigationHistory
                val currentIndex = current.value.historyIndex

                // Remove forward history when navigating to new location
                val trimmedHistory = currentHistory.take(currentIndex + 1)
                trimmedHistory + target
            } else {
                current.value.navigationHistory
            }

            val breadcrumbs = breadcrumbGenerator.getBreadcrumbs(location)
            log(tag) { "Generated breadcrumbs: $breadcrumbs" }

            current.value = current.value.copy(
                currentLocation = location,
                currentBreadcrumbs = breadcrumbs,
                isLoading = false,
                navigationHistory = newHistory,
                historyIndex = if (addToHistory) newHistory.size - 1 else current.value.historyIndex
            )

            val newTitle = location.toString().toCaString()
            info.value = info.value.copy(title = newTitle)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to navigate to $target: $e" }
            current.value = current.value.copy(
                isLoading = false,
                error = e
            )
        }
    }

    private suspend fun loadExtendedData(path: APath) {
        try {
            log(tag, INFO) { "Loading extended data for: $path" }
            current.value = current.value.copy(isLoadingExtended = true)

//            val extendedItems = engine.getContentExtended(path)
//
//            // Update the current location with extended items
//            val currentLoc = current.value.currentLocation
//            if (currentLoc is ExplorerLocation.Directory && currentLoc.path == path) {
//                val updatedLocation = currentLoc.copy(items = extendedItems)
//
//                current.value = current.value.copy(
//                    currentLocation = updatedLocation,
//                    isLoadingExtended = false
//                )
//            } else {
//                // Location changed, extended data no longer relevant
//                current.value = current.value.copy(isLoadingExtended = false)
//            }
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to load extended data: $e" }
            current.value = current.value.copy(isLoadingExtended = false)
        }
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        scope.cancel()
    }

    @Parcelize
    data class Arguments(
        val startPath: APath? = null,
    ) : Workspace.Arguments {
        override val type: Workspace.Type get() = Workspace.Type.EXPLORER
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Arguments?): ExplorerWorkspace
    }
}
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val engine: ExplorerEngine,
    private val dispatcherProvider: DispatcherProvider,
) : Workspace {

    private val tag = logTag("Workspace", "Explorer", id.shortTag)

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

    private val navigationRequests = MutableSharedFlow<NavigationRequest>()

    data class State(
        val currentLocation: ExplorerLocation? = null,
        val navigationHistory: List<ExplorerLocation> = emptyList(),
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
        log(tag, INFO) { "Initialized" }

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
                    navigationRequests.emit(NavigationRequest.ToPath(startPath, addToHistory = false))
                } else {
                    val homeEntry = engine.getHomeEntry()
                    navigationRequests.emit(NavigationRequest.ToLocation(homeEntry, addToHistory = false))
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

    private suspend fun processNavigationRequest(request: NavigationRequest) {
        log(tag, INFO) { "Processing navigation request: $request" }

        when (request) {
            is NavigationRequest.ToPath -> {
                val currentLoc = current.value.currentLocation
                // TODO if we are navigating to the same location again, we  shouldn't set the currentLoc as parent
                val location = ExplorerLocation.Directory(request.path, parent = currentLoc)
                val addToHistory = when {
                    currentLoc is ExplorerLocation.Directory && currentLoc.path == request.path -> false
                    else -> request.addToHistory
                }
                navigateToLocationInternal(location, addToHistory = addToHistory)
            }
            is NavigationRequest.ToLocation -> {
                navigateToLocationInternal(request.location, addToHistory = request.addToHistory)
            }
            is NavigationRequest.ToBreadcrumb -> {
                val location = when (request.target) {
                    is ExplorerLocation.Breadcrumb.Target.Home -> engine.getHomeEntry()
                    is ExplorerLocation.Breadcrumb.Target.Device -> engine.getDevice()
                    is ExplorerLocation.Breadcrumb.Target.Directory -> {
                        // Don't set a parent - let navigateToLocationInternal handle it properly
                        // This prevents the duplicate breadcrumb issue
                        ExplorerLocation.Directory(request.target.path, parent = null)
                    }
                }
                navigateToLocationInternal(location, addToHistory = true)
            }
            NavigationRequest.Back -> {
                val currentHistory = current.value.navigationHistory
                val currentIndex = current.value.historyIndex

                if (currentIndex > 0) {
                    val targetLocation = currentHistory[currentIndex - 1]
                    navigateToLocationInternal(targetLocation, addToHistory = false)
                    current.value = current.value.copy(historyIndex = currentIndex - 1)
                }
            }
            NavigationRequest.Forward -> {
                val currentHistory = current.value.navigationHistory
                val currentIndex = current.value.historyIndex

                if (currentIndex < currentHistory.size - 1) {
                    val targetLocation = currentHistory[currentIndex + 1]
                    navigateToLocationInternal(targetLocation, addToHistory = false)
                    current.value = current.value.copy(historyIndex = currentIndex + 1)
                }
            }
            NavigationRequest.Refresh -> {
                current.value.currentLocation?.let { location ->
                    navigateToLocationInternal(location, addToHistory = false)
                }
            }
            NavigationRequest.Cancel -> {
                // Just reset the loading state, flatMapLatest will have already cancelled the previous operation
                log(tag, INFO) { "Navigation cancel request processed" }
                current.value = current.value.copy(
                    isLoading = false,
                    isLoadingExtended = false,
                )
            }
        }
    }

    fun navigate(request: NavigationRequest) {
        log(tag) { "navigate(): $request" }
        scope.launch {
            log(tag) { "navigate(): Launching $request" }
            navigationRequests.emit(request)
            log(tag) { "navigate(): Submitted $request" }
        }
    }

    private suspend fun navigateToLocationInternal(location: ExplorerLocation, addToHistory: Boolean) {
        try {
            log(tag, INFO) { "Navigating to: $location" }
            current.value = current.value.copy(isLoading = true, error = null)

            // Check for cancellation before starting
            currentCoroutineContext().ensureActive()

            // Load items for Directory locations
            val locationWithItems = when (location) {
                is ExplorerLocation.Home -> location // Already has its items
                is ExplorerLocation.Device -> location // Already has its items
                is ExplorerLocation.Directory -> {
                    if (location.items == null) {
                        // Check for cancellation before loading
                        currentCoroutineContext().ensureActive()

                        // Load basic items for this directory
                        val items = engine.getContent(location.path)

                        // Check for cancellation after loading
                        currentCoroutineContext().ensureActive()

                        val newLocation = location.copy(items = items)

                        // Load extended data in background
                        scope.launch {
                            loadExtendedData(location.path)
                        }

                        newLocation
                    } else {
                        location // Already loaded
                    }
                }
            }

            val newHistory = if (addToHistory) {
                val currentHistory = current.value.navigationHistory
                val currentIndex = current.value.historyIndex

                // Remove forward history when navigating to new location
                val trimmedHistory = currentHistory.take(currentIndex + 1)
                trimmedHistory + locationWithItems
            } else {
                current.value.navigationHistory
            }

            current.value = current.value.copy(
                currentLocation = locationWithItems,
                isLoading = false,
                navigationHistory = newHistory,
                historyIndex = if (addToHistory) newHistory.size - 1 else current.value.historyIndex
            )

            updateInfo(locationWithItems)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to navigate to $location: $e" }
            current.value = current.value.copy(
                isLoading = false,
                error = e
            )
        }
    }

    private fun updateInfo(location: ExplorerLocation) {
        val newTitle = location.displayName ?: "Explorer".toCaString()
        info.value = info.value.copy(title = newTitle)
    }

    private suspend fun loadExtendedData(path: APath) {
        try {
            log(tag, INFO) { "Loading extended data for: $path" }
            current.value = current.value.copy(isLoadingExtended = true)

            // Check for cancellation before loading
            currentCoroutineContext().ensureActive()

            // Load extended data with permissions/ownership
            val extendedItems = engine.getContentExtended(path)

            // Check for cancellation after loading
            currentCoroutineContext().ensureActive()

            // Update the current location with extended items
            val currentLoc = current.value.currentLocation
            if (currentLoc is ExplorerLocation.Directory && currentLoc.path == path) {
                val updatedLocation = currentLoc.copy(items = extendedItems)

                // Update the navigation history with the enhanced items
                val updatedHistory = current.value.navigationHistory.map { loc ->
                    if (loc is ExplorerLocation.Directory && loc.path == path) {
                        updatedLocation
                    } else {
                        loc
                    }
                }

                current.value = current.value.copy(
                    currentLocation = updatedLocation,
                    navigationHistory = updatedHistory,
                    isLoadingExtended = false
                )
            } else {
                // Location changed, extended data no longer relevant
                current.value = current.value.copy(isLoadingExtended = false)
            }
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
        override val type: Workspace.Type
            get() = Workspace.Type.EXPLORER
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Arguments?): ExplorerWorkspace
    }
}
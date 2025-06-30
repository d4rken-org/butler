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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

    data class State(
        val currentLocation: ExplorerLocation? = null,
        val navigationHistory: List<ExplorerLocation> = emptyList(),
        val historyIndex: Int = 0,
        val isLoading: Boolean = false,
        val error: Throwable? = null,
        val progress: Progress.Data? = null,
    ) {
        val canGoBack: Boolean get() = historyIndex > 0
        val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
    }

    init {
        log(tag, INFO) { "Initialized" }
        scope.launch {
            log(tag, INFO) { "Loading initial data... ($arguments)" }
            try {
                current.value = current.value.copy(isLoading = true)

                val startPath = arguments?.startPath
                if (startPath != null) {
                    navigateToInternal(startPath, addToHistory = false)
                } else {
                    val homeEntry = engine.getHomeEntry()
                    current.value = current.value.copy(
                        currentLocation = homeEntry,
                        navigationHistory = listOf(homeEntry),
                        historyIndex = 0,
                        isLoading = false,
                    )
                    updateInfo(homeEntry)
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

    fun navigateTo(path: APath) {
        scope.launch {
            val location = ExplorerLocation.Directory(path)
            navigateToLocationInternal(location, addToHistory = true)
        }
    }

    fun navigateToLocation(location: ExplorerLocation) {
        scope.launch {
            navigateToLocationInternal(location, addToHistory = true)
        }
    }

    fun navigateToBreadcrumb(target: ExplorerLocation.Breadcrumb.Target) {
        scope.launch {
            val location = when (target) {
                is ExplorerLocation.Breadcrumb.Target.Home -> engine.getHomeEntry()
                is ExplorerLocation.Breadcrumb.Target.Device -> engine.getDevice()
                is ExplorerLocation.Breadcrumb.Target.Directory -> ExplorerLocation.Directory(target.path)
            }
            navigateToLocationInternal(location, addToHistory = true)
        }
    }

    fun navigateBack() {
        scope.launch {
            val currentHistory = current.value.navigationHistory
            val currentIndex = current.value.historyIndex

            if (currentIndex > 0) {
                val targetLocation = currentHistory[currentIndex - 1]
                navigateToLocationInternal(targetLocation, addToHistory = false)
                current.value = current.value.copy(historyIndex = currentIndex - 1)
            }
        }
    }

    fun navigateForward() {
        scope.launch {
            val currentHistory = current.value.navigationHistory
            val currentIndex = current.value.historyIndex

            if (currentIndex < currentHistory.size - 1) {
                val targetLocation = currentHistory[currentIndex + 1]
                navigateToLocationInternal(targetLocation, addToHistory = false)
                current.value = current.value.copy(historyIndex = currentIndex + 1)
            }
        }
    }

    fun refresh() {
        scope.launch {
            current.value.currentLocation?.let { location ->
                navigateToLocationInternal(location, addToHistory = false)
            }
        }
    }

    private suspend fun navigateToInternal(path: APath, addToHistory: Boolean) {
        val location = ExplorerLocation.Directory(path)
        navigateToLocationInternal(location, addToHistory)
    }

    private suspend fun navigateToLocationInternal(location: ExplorerLocation, addToHistory: Boolean) {
        try {
            log(tag, INFO) { "Navigating to: $location" }
            current.value = current.value.copy(isLoading = true, error = null)

            // Load items for Directory locations
            val locationWithItems = when (location) {
                is ExplorerLocation.Home -> location // Already has its items
                is ExplorerLocation.Device -> location // Already has its items
                is ExplorerLocation.Directory -> {
                    if (location.items == null) {
                        // Load items for this directory
                        val items = engine.getContent(location.path)
                        location.copy(items = items)
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
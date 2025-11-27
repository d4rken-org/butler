package eu.darken.butler.saver.ui.saver

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.saver.core.SaveOperation
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

@HiltViewModel(assistedFactory = SaverWorkspaceViewModel.Factory::class)
class SaverWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    navController: NavigationController,
    workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
) : ViewModel4(dispatchers, logTag("Saver", "Workspace", id.shortTag, "Page"), navController) {

    private val workspaceSource: Flow<SaverWorkspace?> =
        workspaceProvider.retrieve(id)
            .map { workspace: Workspace<out Workspace.Arguments>? -> workspace as? SaverWorkspace }

    private suspend fun getWorkspace(): SaverWorkspace = workspaceSource.filterNotNull().first()

    data class State(
        val sourceInfo: ContentUriHelper.SourceInfo? = null,
        val destination: APath<*>? = null,
        val filename: String = "",
        val saveState: SaveOperation.State = SaveOperation.State.Idle,
        val callerPackage: String? = null,
    ) {
        val canSave: Boolean
            get() = destination != null
                && filename.isNotBlank()
                && sourceInfo?.isAccessible == true
                && saveState !is SaveOperation.State.Saving

        val isSaving: Boolean
            get() = saveState is SaveOperation.State.Saving

        val isCompleted: Boolean
            get() = saveState is SaveOperation.State.Success

        val hasError: Boolean
            get() = saveState is SaveOperation.State.Error

        val savedPath: APath<*>?
            get() = (saveState as? SaveOperation.State.Success)?.savedPath
    }

    val state: Flow<State> = workspaceSource
        .filterNotNull()
        .flatMapLatest { it.state }
        .map { wsState ->
            State(
                sourceInfo = wsState.sourceInfo,
                destination = wsState.destination,
                filename = wsState.filename,
                saveState = wsState.saveState,
                callerPackage = wsState.callerPackage,
            )
        }

    init {
        // Listen for picker results (SaveAs mode returns both path and filename)
        workspaceRemote.events
            .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
                log(tag, INFO) { "Received picker result: paths=${result.selectedPaths}, filename=${result.filename}" }
                result.selectedPaths.firstOrNull()?.let { path ->
                    val workspace = getWorkspace()
                    workspace.setDestination(path)

                    // Update filename if returned by SaveAs picker
                    result.filename?.let { filename ->
                        workspace.updateFilename(filename)
                    }
                }
            }
            .launchIn(vmScope)
    }

    fun onPickDestination() = launch {
        log(tag) { "onPickDestination()" }
        val workspace = getWorkspace()
        val currentFilename = workspace.state.first().filename
        val currentDestination = workspace.state.first().destination

        workspaceRemote.launchPicker(
            callerWorkspaceId = id,
            startPath = currentDestination,
            selection = PickerConfig.Selection.SaveAs(
                suggestedFilename = currentFilename.ifBlank { "file" }
            ),
        )
    }

    fun onSave() = launch {
        log(tag, INFO) { "onSave()" }
        try {
            getWorkspace().save()
                .onEach { state ->
                    log(tag) { "Save state: $state" }
                }
                .launchIn(vmScope)
        } catch (e: Exception) {
            log(tag, ERROR) { "Save failed: ${e.asLog()}" }
        }
    }

    fun onOpenSavedFile() = launch {
        val workspace = getWorkspace()
        val savedPath = (workspace.state.first().saveState as? SaveOperation.State.Success)?.savedPath
        if (savedPath != null) {
            log(tag, INFO) { "Opening saved file location: ${savedPath.parent}" }
            workspaceRemote.createAndFocus(
                type = Workspace.Type.EXPLORER,
                arguments = ExplorerArguments.Default(startPath = savedPath.parent),
            )
        }
    }

    fun onRefreshAccessibility() = launch {
        log(tag) { "onRefreshAccessibility()" }
        getWorkspace().refreshSourceAccessibility()
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SaverWorkspaceViewModel
    }
}

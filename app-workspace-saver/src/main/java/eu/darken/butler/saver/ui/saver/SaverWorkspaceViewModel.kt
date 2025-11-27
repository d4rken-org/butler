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
import eu.darken.butler.common.files.validation.FilenameValidator
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    private val filenameValidator: FilenameValidator,
) : ViewModel4(dispatchers, logTag("Saver", "Workspace", id.shortTag, "Page"), navController) {

    private val workspaceSource: Flow<SaverWorkspace?> =
        workspaceProvider.retrieve(id)
            .map { workspace: Workspace<out Workspace.Arguments>? -> workspace as? SaverWorkspace }

    private suspend fun getWorkspace(): SaverWorkspace = workspaceSource.filterNotNull().first()

    private val filenameErrorFlow = MutableStateFlow<String?>(null)

    data class State(
        val sourceInfo: ContentUriHelper.SourceInfo? = null,
        val destination: APath<*>? = null,
        val filename: String = "",
        val filenameError: String? = null,
        val saveState: SaveOperation.State = SaveOperation.State.Idle,
        val callerPackage: String? = null,
    ) {
        val canSave: Boolean
            get() = destination != null
                && filename.isNotBlank()
                && filenameError == null
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

    val state: Flow<State> = combine(
        workspaceSource.filterNotNull().flatMapLatest { it.state },
        filenameErrorFlow,
    ) { wsState, filenameError ->
        State(
            sourceInfo = wsState.sourceInfo,
            destination = wsState.destination,
            filename = wsState.filename,
            filenameError = filenameError,
            saveState = wsState.saveState,
            callerPackage = wsState.callerPackage,
        )
    }

    init {
        // Listen for picker results
        workspaceRemote.events
            .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
                log(tag, INFO) { "Received picker result: ${result.selectedPaths}" }
                result.selectedPaths.firstOrNull()?.let { path ->
                    getWorkspace().setDestination(path)
                }
            }
            .launchIn(vmScope)
    }

    fun onFilenameChanged(filename: String) = launch {
        log(tag) { "onFilenameChanged($filename)" }
        val workspace = getWorkspace()
        workspace.updateFilename(filename)

        // Validate filename - requires destination to determine storage context
        val destination = workspace.state.first().destination
        filenameErrorFlow.value = if (filename.isBlank()) {
            "Filename cannot be empty"
        } else if (destination != null) {
            when (val validation = filenameValidator.validate(filename, destination)) {
                is FilenameValidator.ValidationResult.Valid -> null
                is FilenameValidator.ValidationResult.Invalid -> {
                    "Invalid characters: ${validation.invalidChars.joinToString()}"
                }
            }
        } else {
            null // Can't validate without destination
        }
    }

    fun onPickDestination() = launch {
        log(tag) { "onPickDestination()" }
        workspaceRemote.launchPicker(
            callerWorkspaceId = id,
            startPath = null,
            selection = PickerConfig.Selection.DirectorySingle,
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

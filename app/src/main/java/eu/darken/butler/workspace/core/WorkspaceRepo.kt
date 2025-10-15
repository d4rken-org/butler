package eu.darken.butler.workspace.core

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.ui.picker.ExplorerPickerArguments
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.templates.core.TemplatesWorkspace
import eu.darken.butler.workspace.core.operations.OperationsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val templatesWorkspaceFactory: TemplatesWorkspace.Factory,
    private val explorerWorkspaceFactory: ExplorerWorkspace.Factory,
    private val searcherWorkspaceFactory: SearcherWorkspace.Factory,
    private val editorWorkspaceFactory: EditorWorkspace.Factory,
    workspaceSettings: WorkspaceSettings,
    private val operationsManager: OperationsManager,
) : WorkspaceProvider, WorkspaceRemote {

    private val lock = Mutex()
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    private val _events = MutableSharedFlow<WorkspaceEvent>()
    // Track parent-child relationships for picker workspaces (picker → caller)
    private val pickerParents = mutableMapOf<Workspace.Id, Workspace.Id>()
    private val infos: Flow<List<Workspace.Info>> = _workspaces.flatMapLatest { workspaces ->
        if (workspaces.isEmpty()) {
            flowOf(emptyList())
        } else {
            val infoFlows = workspaces.map { it.info }
            combine(infoFlows) { infos -> infos.toList() }
        }
    }

    override val state: Flow<WorkspaceRemote.State> = infos
        .map { workspaceInfos ->
            WorkspaceRemote.State(
                infos = workspaceInfos,
            )
        }
        .setupCommonEventHandlers(TAG, enabled = Bugs.isTrace) { "WorkspaceState" }
        .replayingShare(appScope)

    override val events: Flow<WorkspaceEvent> = _events
        .setupCommonEventHandlers(TAG, enabled = Bugs.isDebug) { "WorkspaceEvents" }
        .replayingShare(appScope)

    override suspend fun emitEvent(event: WorkspaceEvent) {
        log(TAG) { "emitEvent($event)" }
        _events.emit(event)
    }

    private fun create(
        type: Workspace.Type,
        arguments: Workspace.Arguments? = null,
        idToReplace: Workspace.Id? = null,
    ): Workspace.Id {
        log(TAG) { "create($type, $arguments, $idToReplace)" }
        val wip = _workspaces.value.toMutableList()

        val newWorkspace = when (type) {
            Workspace.Type.TEMPLATES -> templatesWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as TemplatesWorkspace.Arguments?
            )
            Workspace.Type.EXPLORER -> explorerWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments
            )
            Workspace.Type.SEARCHER -> searcherWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as SearcherWorkspace.Arguments?
            )
            Workspace.Type.EDITOR -> editorWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as EditorWorkspace.Arguments?
            )
        }
        if (idToReplace != null) {
            val index = wip.indexOfFirst { it.id == idToReplace }
            if (index == -1) throw IllegalStateException("Tab not found")
            log(TAG) { "Replacing workspace at index $index" }
            wip[index] = newWorkspace
        } else {
            wip.add(newWorkspace)
        }

        _workspaces.value = wip

        // Track parent-child relationship for picker workspaces
        if (arguments is ExplorerPickerArguments) {
            val callerId = arguments.callerWorkspaceId
            if (callerId != null) {
                pickerParents[newWorkspace.id] = callerId
                log(TAG) { "Tracked picker relationship: ${newWorkspace.id} -> $callerId" }
            }
        }

        return newWorkspace.id
    }

    override fun retrieve(id: Workspace.Id): Flow<Workspace?> {
        return _workspaces.map { wss -> wss.singleOrNull { it.id == id } }
    }

    override suspend fun execute(action: WorkspaceAction): WorkspaceAction.Result = lock.withLock {
        log(TAG, INFO) { "execute($action)" }
        when (action) {
            is WorkspaceAction.Create -> {
                log(TAG, INFO) { "Creating new workspace with $action" }
                val newId = create(
                    type = action.type,
                    arguments = action.arguments,
                    idToReplace = action.replace
                )
                log(TAG) { "New workspace created with ID $newId, emitting event" }
                _events.emit(
                    WorkspaceEvent.Created(
                        workspaceId = newId,
                        replacedId = action.replace
                    )
                )
                WorkspaceAction.Create.Result(newId)
            }

            is WorkspaceAction.Close -> {
                log(TAG, INFO) { "Closing workspace with id ${action.id}" }

                // Find and close all child pickers owned by this workspace
                val childPickers = pickerParents.filterValues { it == action.id }.keys
                if (childPickers.isNotEmpty()) {
                    log(TAG) { "Auto-closing ${childPickers.size} child picker(s): $childPickers" }
                    childPickers.forEach { childId ->
                        _workspaces.value = _workspaces.value.filter { it.id != childId }
                        pickerParents.remove(childId)
                        _events.emit(WorkspaceEvent.Closed(workspaceId = childId))
                    }
                }

                _workspaces.value = _workspaces.value.filter { it.id != action.id }
                pickerParents.remove(action.id)  // Clean up if this was a picker
                _events.emit(WorkspaceEvent.Closed(workspaceId = action.id))
                WorkspaceAction.Close.Result
            }
            is WorkspaceAction.Reorder -> {
                log(TAG, INFO) { "Reordering workspaces: ${action.workspaceIds}" }

                val current = _workspaces.value
                log(TAG) { "BEFORE re-order:\n${current.joinToString("\n")}" }
                val reordered = action.workspaceIds.mapNotNull { id ->
                    current.find { it.id == id }
                }
                log(TAG) { "AFTER re-order:\n${reordered.joinToString("\n")}" }

                if (reordered.size != current.size) {
                    log(TAG, ERROR) { "Reorder failed: size mismatch. Expected ${current.size}, got ${reordered.size}" }
                    return WorkspaceAction.Reorder.Result(false)
                }

                _workspaces.value = reordered
                _events.emit(WorkspaceEvent.Reordered(workspaceIds = action.workspaceIds))
                WorkspaceAction.Reorder.Result(true)
            }
            WorkspaceAction.CloseAll -> {
                log(TAG, INFO) { "Closing all workspaces" }
                _workspaces.value.forEach {
                    it.release()
                    operationsManager.removeWorkspace(it.id)
                }
                _workspaces.value = emptyList()
                pickerParents.clear()  // Clean up all picker relationships
                _events.emit(WorkspaceEvent.AllClosed)
                WorkspaceAction.CloseAll.Result
            }
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "Repo")
    }

}

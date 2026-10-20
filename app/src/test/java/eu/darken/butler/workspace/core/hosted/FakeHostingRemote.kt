package eu.darken.butler.workspace.core.hosted

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Just enough of a repository for hosted pickers: creates and closes them, and lets a test emit events. */
internal class FakeHostingRemote : WorkspaceRemote {
    val infos = MutableStateFlow<List<Workspace.Info>>(emptyList())
    val eventFlow = MutableSharedFlow<WorkspaceEvent>(extraBufferCapacity = 16)
    val actions = mutableListOf<WorkspaceAction>()

    /** When set, a Create suspends on it before the workspace exists. */
    var createGate: CompletableDeferred<Unit>? = null

    /** When true, Close leaves the workspace open, like a busy one. */
    var refuseClose = false

    override val state = infos.map { WorkspaceRemote.State(infos = it) }
    override val events = eventFlow
    override suspend fun emitEvent(event: WorkspaceEvent) = eventFlow.emit(event)
    /** Runs once, right after the next [peekInfos] took its snapshot, to change things behind the caller's back. */
    var afterNextPeek: (suspend () -> Unit)? = null

    override suspend fun peekInfos(): List<Workspace.Info> {
        val snapshot = infos.value
        afterNextPeek?.also { afterNextPeek = null }?.invoke()
        return snapshot
    }

    override suspend fun execute(action: WorkspaceAction): WorkspaceAction.Result {
        actions += action
        return when (action) {
            is WorkspaceAction.Create -> {
                createGate?.await()
                val arguments = action.arguments as ExplorerArguments.Picker
                val id = action.id!!
                infos.update {
                    it + Workspace.Info(
                        id = id,
                        type = action.type,
                        title = "Picker".toCaString(),
                        callerWorkspaceId = arguments.callerWorkspaceId,
                        isHosted = arguments.isHostedByNavigation,
                    )
                }
                WorkspaceAction.Create.Result.Success(id)
            }

            is WorkspaceAction.Close -> {
                if (!refuseClose) {
                    infos.update { all -> all.filterNot { it.id == action.id } }
                    eventFlow.emit(WorkspaceEvent.Closed(workspaceId = action.id))
                }
                WorkspaceAction.Close.Result
            }

            else -> error("Unexpected $action")
        }
    }

    val createdArguments: ExplorerArguments.Picker
        get() = actions.filterIsInstance<WorkspaceAction.Create>().single().arguments as ExplorerArguments.Picker

    val closes: List<WorkspaceAction.Close>
        get() = actions.filterIsInstance<WorkspaceAction.Close>()
}

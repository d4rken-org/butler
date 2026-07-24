package eu.darken.butler.workspace.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stand-in for a saved workspace that has not been instantiated yet (on-demand session restore).
 *
 * It holds the already deserialized [heldArguments], owns no scope, engine or I/O, and reports
 * [Workspace.LifecycleState.Dormant] until [WorkspaceAction.Hydrate] swaps in the real instance
 * built by the type's [WorkspaceFactory].
 *
 * Deliberately `internal` to the app module: the UI never composes a typed page host for a dormant
 * workspace and [WorkspaceProvider.retrieve] hides it, so no typed consumer can cast the stand-in.
 */
internal class DormantWorkspace(
    override val id: Workspace.Id,
    override val type: Workspace.Type,
    val heldArguments: Workspace.Arguments,
) : Workspace<Workspace.Arguments> {

    // Seeded through the same derivation real workspaces use, so contentPath, callerWorkspaceId and
    // modalPresentation are correct while dormant (content dedup and lifecycle decisions read them).
    private val _info = MutableStateFlow(
        initialInfo(
            title = type.label,
            arguments = heldArguments,
        ).copy(lifecycleState = Workspace.LifecycleState.Dormant())
    )

    override val info: StateFlow<Workspace.Info> = _info.asStateFlow()

    fun markHydrationError(error: Throwable) {
        _info.value = _info.value.copy(lifecycleState = Workspace.LifecycleState.Dormant(error))
    }

    override suspend fun createArguments(): Workspace.Arguments = heldArguments

    override fun toString(): String = "DormantWorkspace($id, $type)"
}

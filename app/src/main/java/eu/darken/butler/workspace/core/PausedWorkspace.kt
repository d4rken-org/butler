package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.CaString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stand-in for a paused workspace: either restored from a session without ever being instantiated,
 * or a live workspace whose instance was released via [WorkspaceAction.Pause].
 *
 * It holds the already deserialized [heldArguments], owns no scope, engine or I/O, and reports
 * [Workspace.LifecycleState.Paused] until [WorkspaceAction.Resume] swaps in the real instance
 * built by the type's [WorkspaceFactory].
 *
 * [title] and [subtitle] come from the type's [WorkspaceFactory.deriveDisplay] — the stand-in stays
 * dumb and never looks up a factory or decides a fallback itself; that ownership sits with the repo.
 * [carriedInfo] takes precedence when a LIVE tab is paused, so the tab keeps the identity the user
 * was just looking at instead of one re-derived from the captured arguments.
 *
 * Deliberately `internal` to the app module: the UI never composes a typed page host for a paused
 * workspace and [WorkspaceProvider.retrieve] hides it, so no typed consumer can cast the stand-in.
 */
internal class PausedWorkspace(
    override val id: Workspace.Id,
    override val type: Workspace.Type,
    val heldArguments: Workspace.Arguments,
    title: CaString,
    subtitle: CaString? = null,
    /**
     * Info of the live instance this stand-in replaces ([WorkspaceAction.Pause]), so the tab keeps
     * exactly what the user last saw - title, subtitle and content path - while paused. Null when
     * nothing was ever instantiated (session restore), where [title]/[subtitle] describe the tab.
     */
    carriedInfo: Workspace.Info? = null,
) : Workspace<Workspace.Arguments> {

    // Seeded through the same derivation real workspaces use, so contentPath, callerWorkspaceId and
    // modalPresentation are correct while paused (content dedup and lifecycle decisions read them).
    private val _info = MutableStateFlow(
        (
            carriedInfo ?: initialInfo(
                title = title,
                subtitle = subtitle,
                arguments = heldArguments,
            )
            ).copy(
            lifecycleState = Workspace.LifecycleState.Paused(),
            operationCount = 0,
            attentionCount = 0,
            hasUnsavedChanges = false,
            isPausable = true,
        )
    )

    override val info: StateFlow<Workspace.Info> = _info.asStateFlow()

    fun markResumeError(error: Throwable) {
        _info.value = _info.value.copy(lifecycleState = Workspace.LifecycleState.Paused(error))
    }

    override suspend fun createArguments(): Workspace.Arguments = heldArguments

    override fun toString(): String = "PausedWorkspace($id, $type)"
}

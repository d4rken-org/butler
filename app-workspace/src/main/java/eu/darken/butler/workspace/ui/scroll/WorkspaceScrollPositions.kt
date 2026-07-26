package eu.darken.butler.workspace.ui.scroll

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.SlotLease
import eu.darken.butler.workspace.ui.restore.WorkspaceSlotRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers where each workspace list/grid was scrolled to, one slot per list - e.g. Explorer keeps
 * a list and a grid slot per directory.
 */
@Singleton
class WorkspaceScrollPositions @Inject constructor() : WorkspaceSlotRegistry<WorkspaceScrollPosition>(
    tag = TAG,
    maxSlotsPerWorkspace = MAX_SLOTS_PER_WORKSPACE,
) {

    fun positionFor(workspaceId: Workspace.Id, slot: String): SlotLease<WorkspaceScrollPosition> =
        leaseFor(workspaceId, slot)

    companion object {
        const val MAX_SLOTS_PER_WORKSPACE = 16
        private val TAG = logTag("Workspace", "ScrollPositions")
    }
}

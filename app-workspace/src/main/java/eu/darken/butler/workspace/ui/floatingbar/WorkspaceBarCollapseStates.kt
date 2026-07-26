package eu.darken.butler.workspace.ui.floatingbar

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.SlotLease
import eu.darken.butler.workspace.ui.restore.WorkspaceSlotRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers how far each workspace's floating bar stacks were scroll-collapsed.
 *
 * Restoring a list position is not enough on its own: a collapsed top bar reserves less content
 * padding than an expanded one, so coming back with the bar in a different state shifts the restored
 * row by the difference and reads as "off by one row".
 *
 * One slot per [BarPosition], holding a single fraction for the whole stack - which is the
 * granularity the collapse system actually has, since the nested-scroll handler drives every
 * non-static bar of a stack to one shared target.
 */
@Singleton
class WorkspaceBarCollapseStates @Inject constructor() : WorkspaceSlotRegistry<Float>(
    tag = TAG,
    maxSlotsPerWorkspace = MAX_SLOTS_PER_WORKSPACE,
) {

    fun collapseFor(workspaceId: Workspace.Id, position: BarPosition): SlotLease<Float> =
        leaseFor(workspaceId, position.name)

    companion object {
        // Two positions today; the bound only exists so a future slot key can't grow unbounded.
        const val MAX_SLOTS_PER_WORKSPACE = 8
        private val TAG = logTag("Workspace", "BarCollapse")
    }
}

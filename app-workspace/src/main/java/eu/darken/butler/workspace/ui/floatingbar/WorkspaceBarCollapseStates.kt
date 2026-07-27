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
 * One slot per [BarPosition], holding each non-static bar's fraction by its stable key. Per bar
 * rather than per stack: bars in one stack diverge at rest, because a bar that becomes visible again
 * snaps its own fraction to 0 while the others stay collapsed.
 */
@Singleton
class WorkspaceBarCollapseStates @Inject constructor() : WorkspaceSlotRegistry<Map<String, Float>>(
    maxSlotsPerWorkspace = MAX_SLOTS_PER_WORKSPACE,
) {

    override val tag: String = TAG

    /**
     * The slot key is [BarPosition.persistedKey] and the map keys are the bars' own keys - both end up
     * verbatim in the persisted session blob, so renaming either orphans stored user state.
     */
    fun collapseFor(workspaceId: Workspace.Id, position: BarPosition): SlotLease<Map<String, Float>> =
        leaseFor(workspaceId, position.persistedKey)

    companion object {
        // Two positions today; the bound only exists so a future slot key can't grow unbounded.
        const val MAX_SLOTS_PER_WORKSPACE = 8
        private val TAG = logTag("Workspace", "BarCollapse")
    }
}

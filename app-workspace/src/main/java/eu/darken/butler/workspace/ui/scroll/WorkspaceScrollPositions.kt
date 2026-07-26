package eu.darken.butler.workspace.ui.scroll

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers where each workspace list/grid was scrolled to.
 *
 * The state has to live outside composition: panes are distinct call sites, the single-pane pager
 * disposes swiped-away pages and a layout change swaps the whole subtree, so every relevant
 * transition destroys the composition that owns a `LazyListState`.
 */
@Singleton
class WorkspaceScrollPositions @Inject constructor() {

    /**
     * What a composable holds while it owns a slot.
     *
     * [generation] is what stops a page that is being disposed *because its workspace closed* from
     * resurrecting the entry after [forget]: event handling, repo state collection and Compose
     * disposal have no defined order, so a late [record] is expected rather than exceptional.
     */
    data class Lease(
        val workspaceId: Workspace.Id,
        val slot: String,
        val generation: Long,
        val saved: WorkspaceScrollPosition?,
    )

    private val lock = Any()
    private val positions = mutableMapOf<Workspace.Id, LinkedHashMap<String, WorkspaceScrollPosition>>()
    private val generations = mutableMapOf<Workspace.Id, Long>()
    private var generationCounter = 0L
    private var clearGeneration = 0L

    private val _changes = MutableStateFlow(0L)

    /**
     * Monotonic counter bumped by every accepted [record], used purely as a save trigger. A counter
     * rather than a map snapshot avoids rebuilding immutable maps on every scroll frame.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun generationOf(workspaceId: Workspace.Id): Long =
        maxOf(clearGeneration, generations[workspaceId] ?: 0L)

    private fun slotsOf(workspaceId: Workspace.Id): LinkedHashMap<String, WorkspaceScrollPosition> =
        positions.getOrPut(workspaceId) {
            object : LinkedHashMap<String, WorkspaceScrollPosition>(MAX_SLOTS_PER_WORKSPACE, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, WorkspaceScrollPosition>,
                ): Boolean = size > MAX_SLOTS_PER_WORKSPACE
            }
        }

    fun positionFor(workspaceId: Workspace.Id, slot: String): Lease = synchronized(lock) {
        val saved = positions[workspaceId]?.get(slot)
        Lease(
            workspaceId = workspaceId,
            slot = slot,
            generation = generationOf(workspaceId),
            saved = saved,
        )
    }

    fun record(lease: Lease, position: WorkspaceScrollPosition) {
        val accepted = synchronized(lock) {
            if (lease.generation != generationOf(lease.workspaceId)) {
                false
            } else {
                val slots = slotsOf(lease.workspaceId)
                if (slots[lease.slot] == position) {
                    false
                } else {
                    slots[lease.slot] = position
                    true
                }
            }
        }
        if (accepted) _changes.update { it + 1 }
    }

    fun forget(workspaceId: Workspace.Id) = synchronized(lock) {
        log(TAG) { "forget($workspaceId)" }
        positions.remove(workspaceId)
        generations[workspaceId] = ++generationCounter
    }

    fun clear() = synchronized(lock) {
        log(TAG) { "clear()" }
        positions.clear()
        generations.clear()
        clearGeneration = ++generationCounter
    }

    /**
     * Seeds persisted positions. Slots already recorded by a live workspace win, so a restore that
     * lands late can never overwrite what the user is currently looking at.
     */
    fun restore(saved: Map<Workspace.Id, Map<String, WorkspaceScrollPosition>>) = synchronized(lock) {
        log(TAG) { "restore(${saved.size} workspaces)" }
        saved.forEach { (workspaceId, slots) ->
            val target = slotsOf(workspaceId)
            slots.forEach { (slot, position) ->
                if (!target.containsKey(slot)) target[slot] = position
            }
        }
    }

    fun snapshot(): Map<Workspace.Id, Map<String, WorkspaceScrollPosition>> = synchronized(lock) {
        positions.mapValues { (_, slots) -> slots.toMap() }
    }

    companion object {
        const val MAX_SLOTS_PER_WORKSPACE = 16
        private val TAG = logTag("Workspace", "ScrollPositions")
    }
}

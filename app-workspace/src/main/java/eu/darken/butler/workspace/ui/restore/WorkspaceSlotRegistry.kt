package eu.darken.butler.workspace.ui.restore

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a composable holds while it owns a slot.
 *
 * [generation] is what stops a page that is being disposed *because its workspace closed* from
 * resurrecting the entry after [WorkspaceSlotRegistry.forget]: event handling, repo state collection
 * and Compose disposal have no defined order, so a late record is expected rather than exceptional.
 */
data class SlotLease<T>(
    val workspaceId: Workspace.Id,
    val slot: String,
    val generation: Long,
    val saved: T?,
)

/**
 * Per-workspace UI state that has to outlive composition.
 *
 * Panes are distinct call sites, the single-pane pager disposes swiped-away pages and a layout
 * change swaps the whole subtree, so every transition this exists for destroys the composition that
 * owned the state. Subclasses are `@Singleton`s that name their own domain (scroll positions,
 * floating bar collapse); the leasing, LRU and generation semantics live here so there is only one
 * copy of them to reason about.
 */
abstract class WorkspaceSlotRegistry<T : Any>(
    private val tag: String,
    private val maxSlotsPerWorkspace: Int = DEFAULT_MAX_SLOTS,
) {

    private val lock = Any()
    private val entries = mutableMapOf<Workspace.Id, LinkedHashMap<String, T>>()
    private val generations = mutableMapOf<Workspace.Id, Long>()
    private var generationCounter = 0L
    private var clearGeneration = 0L

    private val _changes = MutableStateFlow(0L)

    /**
     * Monotonic counter bumped by every accepted record, used purely as a save trigger. A counter
     * rather than a map snapshot avoids rebuilding immutable maps on every change.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun generationOf(workspaceId: Workspace.Id): Long =
        maxOf(clearGeneration, generations[workspaceId] ?: 0L)

    private fun slotsOf(workspaceId: Workspace.Id): LinkedHashMap<String, T> =
        entries.getOrPut(workspaceId) {
            object : LinkedHashMap<String, T>(maxSlotsPerWorkspace, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>): Boolean =
                    size > maxSlotsPerWorkspace
            }
        }

    protected fun leaseFor(workspaceId: Workspace.Id, slot: String): SlotLease<T> = synchronized(lock) {
        SlotLease(
            workspaceId = workspaceId,
            slot = slot,
            generation = generationOf(workspaceId),
            saved = entries[workspaceId]?.get(slot),
        )
    }

    fun record(lease: SlotLease<T>, value: T) {
        val accepted = synchronized(lock) {
            val currentGeneration = generationOf(lease.workspaceId)
            if (lease.generation != currentGeneration) {
                // Only the rejected path is logged; the accepted one can run once per frame
                log(tag, VERBOSE) {
                    "Dropping stale record for ${lease.workspaceId}/${lease.slot}" +
                        " (lease=${lease.generation}, current=$currentGeneration): $value"
                }
                false
            } else {
                val slots = slotsOf(lease.workspaceId)
                if (slots[lease.slot] == value) {
                    false
                } else {
                    slots[lease.slot] = value
                    true
                }
            }
        }
        if (accepted) _changes.update { it + 1 }
    }

    fun forget(workspaceId: Workspace.Id) = synchronized(lock) {
        log(tag) { "forget($workspaceId)" }
        entries.remove(workspaceId)
        generations[workspaceId] = ++generationCounter
    }

    fun clear() = synchronized(lock) {
        log(tag) { "clear()" }
        entries.clear()
        generations.clear()
        clearGeneration = ++generationCounter
    }

    /**
     * Seeds persisted values. Slots already recorded by a live workspace win, so a restore that
     * lands late can never overwrite what the user is currently looking at.
     */
    fun restore(saved: Map<Workspace.Id, Map<String, T>>) = synchronized(lock) {
        log(tag) { "restore(${saved.size} workspaces)" }
        saved.forEach { (workspaceId, slots) ->
            val target = slotsOf(workspaceId)
            slots.forEach { (slot, value) ->
                if (!target.containsKey(slot)) target[slot] = value
            }
        }
    }

    fun snapshot(): Map<Workspace.Id, Map<String, T>> = synchronized(lock) {
        entries.mapValues { (_, slots) -> slots.toMap() }
    }

    companion object {
        const val DEFAULT_MAX_SLOTS = 16
    }
}

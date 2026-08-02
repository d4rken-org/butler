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
    private val maxSlotsPerWorkspace: Int = DEFAULT_MAX_SLOTS,
) {

    /** Log tag of the concrete registry, so its lines stay greppable as one family. */
    protected abstract val tag: String

    private val lock = Any()
    private val entries = mutableMapOf<Workspace.Id, LinkedHashMap<String, T>>()
    private val generations = mutableMapOf<Workspace.Id, Long>()

    /**
     * Generations of individual slots, raised by [mutateSlot] so a lease taken before it cannot write
     * the mutated value back, while leases of the workspace's other slots stay usable.
     */
    private val slotGenerations = mutableMapOf<Workspace.Id, MutableMap<String, Long>>()
    private var generationCounter = 0L
    private var clearGeneration = 0L

    private val _changes = MutableStateFlow(0L)

    /**
     * Monotonic counter bumped by every accepted record, used purely as a save trigger. A counter
     * rather than a map snapshot avoids rebuilding immutable maps on every change.
     *
     * Deliberately NOT bumped by [restore], [forget] and [clear]: those either re-save what was just
     * loaded or tear state down that is going away anyway. A [mutateSlot] that changed something does
     * bump it - a preference the user just cleared has to reach the session row.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private val _mutations = MutableStateFlow(0L)

    /**
     * Monotonic counter bumped by EVERY state change, including the ones [changes] ignores.
     *
     * Observation ([WorkspaceViewPrefs.observe]) rides on this rather than on [changes], because an
     * observer that starts collecting before session restore would otherwise never see the restored
     * value.
     */
    val mutations: StateFlow<Long> = _mutations.asStateFlow()

    /**
     * [slot] narrows the generation to the one a lease for that specific slot has to match.
     *
     * Only [mutateSlot] ever raises a per-slot generation, so for the lease-based registries this is
     * identical to the workspace-wide value it always was.
     */
    private fun generationOf(workspaceId: Workspace.Id, slot: String? = null): Long = maxOf(
        maxOf(clearGeneration, generations[workspaceId] ?: 0L),
        slot?.let { slotGenerations[workspaceId]?.get(it) } ?: 0L,
    )

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
            generation = generationOf(workspaceId, slot),
            saved = entries[workspaceId]?.get(slot),
        )
    }

    /**
     * Reads a slot WITHOUT marking it recently used, for observation.
     *
     * [leaseFor] goes through the access-ordered map's `get`, so observing a slot while unrelated
     * slots are written would keep pinning the observed one against LRU eviction. Iterating the entry
     * set sidesteps that; anything that intends to write back still takes a lease.
     */
    protected fun peekFor(workspaceId: Workspace.Id, slot: String): T? = synchronized(lock) {
        entries[workspaceId]?.entries?.firstOrNull { it.key == slot }?.value
    }

    fun record(lease: SlotLease<T>, value: T) {
        val accepted = synchronized(lock) {
            val currentGeneration = generationOf(lease.workspaceId, lease.slot)
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
        if (accepted) bump(alsoChanges = true)
    }

    /**
     * Atomic read-modify-write of a single slot; [transform] returning null deletes it.
     *
     * One operation rather than a read plus a write, because a lease taken before an unrelated
     * mutation is stale by the time it is recorded and would resurrect a value that was just cleared.
     * A deletion that empties a workspace's slot map drops the outer entry too, so [snapshot] never
     * carries `id -> {}` residue.
     */
    fun mutateSlot(workspaceId: Workspace.Id, slot: String, transform: (T?) -> T?) {
        val changed = synchronized(lock) {
            val existing = entries[workspaceId]
            val old = existing?.get(slot)
            val new = transform(old)
            if (new == old) {
                false
            } else {
                if (new == null) {
                    existing?.remove(slot)
                    if (existing != null && existing.isEmpty()) entries.remove(workspaceId)
                } else {
                    slotsOf(workspaceId)[slot] = new
                }
                slotGenerations.getOrPut(workspaceId) { mutableMapOf() }[slot] = ++generationCounter
                true
            }
        }
        if (changed) bump(alsoChanges = true)
    }

    fun forget(workspaceId: Workspace.Id) {
        val removed = synchronized(lock) {
            log(tag) { "forget($workspaceId)" }
            val removed = entries.remove(workspaceId) != null
            slotGenerations.remove(workspaceId)
            generations[workspaceId] = ++generationCounter
            removed
        }
        if (removed) bump(alsoChanges = false)
    }

    fun clear() {
        val hadEntries = synchronized(lock) {
            log(tag) { "clear()" }
            val hadEntries = entries.isNotEmpty()
            entries.clear()
            generations.clear()
            slotGenerations.clear()
            clearGeneration = ++generationCounter
            hadEntries
        }
        if (hadEntries) bump(alsoChanges = false)
    }

    /**
     * Seeds persisted values. Slots already recorded by a live workspace win, so a restore that
     * lands late can never overwrite what the user is currently looking at.
     */
    fun restore(saved: Map<Workspace.Id, Map<String, T>>) {
        val seeded = synchronized(lock) {
            log(tag) { "restore(${saved.size} workspaces)" }
            var seeded = false
            saved.forEach { (workspaceId, slots) ->
                // An empty inner map must not materialize an entry, or snapshot() would carry it back
                if (slots.isEmpty()) return@forEach
                val target = slotsOf(workspaceId)
                slots.forEach { (slot, value) ->
                    if (!target.containsKey(slot)) {
                        target[slot] = value
                        seeded = true
                    }
                }
            }
            seeded
        }
        if (seeded) bump(alsoChanges = false)
    }

    private fun bump(alsoChanges: Boolean) {
        if (alsoChanges) _changes.update { it + 1 }
        _mutations.update { it + 1 }
    }

    fun snapshot(): Map<Workspace.Id, Map<String, T>> = synchronized(lock) {
        entries.mapValues { (_, slots) -> slots.toMap() }
    }

    companion object {
        const val DEFAULT_MAX_SLOTS = 16
    }
}

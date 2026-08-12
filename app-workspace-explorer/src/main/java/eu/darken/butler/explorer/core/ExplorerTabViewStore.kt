package eu.darken.butler.explorer.core

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed facade over the generic view-pref store for a tab's view style and filters.
 *
 * Reads are synchronous because the tab's controller needs both at construction and is the only
 * writer for the tab's lifetime.
 *
 * A payload that cannot be decoded degrades to the default for that one slot - the tab's other
 * preference and other tabs stay unaffected - and is RETAINED until the next write for that slot
 * overwrites or deletes it.
 */
@Singleton
class ExplorerTabViewStore @Inject constructor(
    private val viewPrefs: WorkspaceViewPrefs,
    private val json: Json,
) {

    fun currentViewStyle(id: Workspace.Id): ExplorerViewStyle? {
        val stored = viewPrefs.current(id, SLOT_VIEWSTYLE) ?: return null
        return try {
            json.decodeFromJsonElement(ExplorerViewStyle.serializer(), stored)
        } catch (e: Exception) {
            log(TAG, WARN) { "Tab view style is unreadable and is IGNORED: ${e.asLog()}" }
            null
        }
    }

    /**
     * Stores [style] only if the tab has none yet, as one atomic slot mutation.
     *
     * A read plus a write would let a restore that lands in between lose to the global default.
     */
    fun ensureViewStyle(id: Workspace.Id, style: ExplorerViewStyle) {
        viewPrefs.mutateSlot(id, SLOT_VIEWSTYLE) { stored ->
            stored ?: json.encodeToJsonElement(ExplorerViewStyle.serializer(), style)
        }
    }

    fun setViewStyle(id: Workspace.Id, style: ExplorerViewStyle) {
        viewPrefs.mutateSlot(id, SLOT_VIEWSTYLE) {
            json.encodeToJsonElement(ExplorerViewStyle.serializer(), style)
        }
    }

    fun currentFilter(id: Workspace.Id): FilterState {
        val stored = viewPrefs.current(id, SLOT_FILTER) ?: return FilterState()
        return try {
            json.decodeFromJsonElement(FilterState.serializer(), stored)
        } catch (e: Exception) {
            log(TAG, WARN) { "Tab filter state is unreadable and is DISCARDED: ${e.asLog()}" }
            FilterState()
        }
    }

    /** A filter reset deletes the slot, so a tab the user cleared leaves no residue in the session row. */
    fun setFilter(id: Workspace.Id, state: FilterState) {
        viewPrefs.mutateSlot(id, SLOT_FILTER) {
            state
                .takeUnless { it == FilterState() }
                ?.let { json.encodeToJsonElement(FilterState.serializer(), it) }
        }
    }

    companion object {
        /** Wire contract, together with the [ExplorerViewStyle] payload shape. */
        const val SLOT_VIEWSTYLE = "explorer.viewstyle"

        /** Wire contract, together with the [FilterState] payload shape. */
        const val SLOT_FILTER = "explorer.filter"
        private val TAG = logTag("Explorer", "TabViewStore")
    }
}

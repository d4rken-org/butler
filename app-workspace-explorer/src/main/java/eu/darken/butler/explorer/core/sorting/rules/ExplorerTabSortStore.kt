package eu.darken.butler.explorer.core.sorting.rules

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed facade over the generic view-pref store for Explorer's per-tab sort overrides.
 *
 * A payload that cannot be decoded degrades to "no overrides" for that one tab and is logged - it
 * must not take the tab's other preferences or another tab's overrides with it.
 */
@Singleton
class ExplorerTabSortStore @Inject constructor(
    private val viewPrefs: WorkspaceViewPrefs,
    private val json: Json,
) {

    fun overridesFor(id: Workspace.Id): Flow<TabSortOverrides> = viewPrefs
        .observe(id, SLOT)
        .map { decode(it) }

    /**
     * Decode-transform-encode as one atomic slot mutation; a result that carries nothing deletes the
     * slot, so a tab the user reset leaves no residue in the session row.
     */
    fun update(id: Workspace.Id, transform: (TabSortOverrides) -> TabSortOverrides) {
        viewPrefs.mutateSlot(id, SLOT) { stored ->
            transform(decode(stored))
                .takeUnless { it.isEmpty }
                ?.let { json.encodeToJsonElement(TabSortOverrides.serializer(), it) }
        }
    }

    fun clear(id: Workspace.Id) = viewPrefs.mutateSlot(id, SLOT) { null }

    private fun decode(stored: JsonElement?): TabSortOverrides {
        if (stored == null) return TabSortOverrides()
        return try {
            json.decodeFromJsonElement(TabSortOverrides.serializer(), stored)
        } catch (e: Exception) {
            log(TAG, WARN) { "Tab sort overrides are unreadable and are DISCARDED: ${e.asLog()}" }
            TabSortOverrides()
        }
    }

    companion object {
        /** Wire contract, together with the [TabSortOverrides] payload shape. */
        const val SLOT = "explorer.sort"
        private val TAG = logTag("Explorer", "Sorting", "TabSortStore")
    }
}

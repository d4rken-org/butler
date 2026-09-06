package eu.darken.butler.searcher.core

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
 * Typed facade over the generic view-pref store for a tab's view style.
 *
 * Reads are synchronous because the tab's page needs the value at construction.
 *
 * A payload that cannot be decoded degrades to the default for that one slot and is RETAINED until
 * the next write for that slot overwrites it.
 */
@Singleton
class SearcherTabViewStore @Inject constructor(
    private val viewPrefs: WorkspaceViewPrefs,
    private val json: Json,
) {

    fun currentViewStyle(id: Workspace.Id): SearcherViewStyle? = decodeViewStyle(viewPrefs.current(id, SLOT_VIEWSTYLE))

    /** The tab's style as it changes, including writes another tab's page made to this slot. */
    fun observeViewStyle(id: Workspace.Id): Flow<SearcherViewStyle?> = viewPrefs
        .observe(id, SLOT_VIEWSTYLE)
        .map { decodeViewStyle(it) }

    /**
     * Stores [style] only if the tab has none yet, as one atomic slot mutation.
     *
     * A read plus a write would let a restore that lands in between lose to the global default.
     */
    fun ensureViewStyle(id: Workspace.Id, style: SearcherViewStyle) {
        viewPrefs.mutateSlot(id, SLOT_VIEWSTYLE) { stored ->
            stored ?: json.encodeToJsonElement(SearcherViewStyle.serializer(), style)
        }
    }

    fun setViewStyle(id: Workspace.Id, style: SearcherViewStyle) {
        viewPrefs.mutateSlot(id, SLOT_VIEWSTYLE) {
            json.encodeToJsonElement(SearcherViewStyle.serializer(), style)
        }
    }

    private fun decodeViewStyle(stored: JsonElement?): SearcherViewStyle? {
        if (stored == null) return null
        return try {
            json.decodeFromJsonElement(SearcherViewStyle.serializer(), stored)
        } catch (e: Exception) {
            log(TAG, WARN) { "Tab view style is unreadable and is IGNORED: ${e.asLog()}" }
            null
        }
    }

    companion object {
        /** Wire contract, together with the [SearcherViewStyle] payload shape. */
        const val SLOT_VIEWSTYLE = "searcher.viewstyle"
        private val TAG = logTag("Searcher", "TabViewStore")
    }
}

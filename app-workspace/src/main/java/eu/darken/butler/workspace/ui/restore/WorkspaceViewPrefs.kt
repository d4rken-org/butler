package eu.darken.butler.workspace.ui.restore

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-workspace view preferences that survive a tab restore but die with the tab.
 *
 * Payloads stay opaque here: the slot key and the JSON shape below it are wire contract owned by the
 * writing module (e.g. Explorer's per-folder sort overrides), so a malformed payload surfaces at that
 * module's decode - isolated to one tab and one slot - instead of taking the whole registry down.
 */
@Singleton
class WorkspaceViewPrefs @Inject constructor() : WorkspaceSlotRegistry<JsonElement>(
    maxSlotsPerWorkspace = MAX_SLOTS_PER_WORKSPACE,
) {

    override val tag: String = TAG

    /**
     * Current payload of [slot], re-emitted on every registry change - including [restore], which is
     * why this rides on `mutations` rather than the save-trigger counter.
     */
    fun observe(id: Workspace.Id, slot: String): Flow<JsonElement?> = mutations
        .map { peekFor(id, slot) }
        .distinctUntilChanged()

    /**
     * Current payload of [slot], read synchronously.
     *
     * For state a workspace has to have at construction time - a restored tab's page is composed as
     * soon as it is registered, so a value that only arrives with the first flow emission would be
     * one frame too late.
     */
    fun current(id: Workspace.Id, slot: String): JsonElement? = peekFor(id, slot)

    companion object {
        const val MAX_SLOTS_PER_WORKSPACE = 8
        private val TAG = logTag("Workspace", "ViewPrefs")
    }
}

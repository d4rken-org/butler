package eu.darken.butler.workspace.contracts.history

import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating a History workspace tab. Multi-instance: each open tab carries its own
 * [HistoryFilter] so the user can keep e.g. an unfiltered tab plus a "Failed only" tab plus a
 * tab scoped to `/sdcard/DCIM` simultaneously.
 *
 * The filter survives session restore via [Workspace.createArguments] returning the current
 * filter; both [Parcelize] (Android IPC) and [Serializable] (kotlinx JSON for session DB) apply.
 */
@Serializable
sealed interface HistoryArguments : Workspace.Arguments {
    val filter: HistoryFilter

    /**
     * Id of the entry the tab opens on, i.e. the id of the operation it records. One-shot: the tab
     * consumes it once the entry exists and [Workspace.createArguments] never reports it back, so a
     * restored session doesn't reopen the entry's details.
     */
    val focusEntryId: String?

    override val type: Workspace.Type get() = Workspace.Type.HISTORY

    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        override val filter: HistoryFilter = HistoryFilter(),
        override val focusEntryId: String? = null,
    ) : HistoryArguments
}

package eu.darken.butler.workspace.core.operations.history

import android.os.Parcelable
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Per-history-tab filter state. Empty sets mean "no filter for this dimension".
 *
 * Both [Parcelable] (Android IPC) and [Serializable] (kotlinx serialization for session restore via
 * [eu.darken.butler.workspace.ui.session.WorkspaceSessionManager], which round-trips arguments as JSON).
 */
@Parcelize
@Serializable
data class HistoryFilter(
    val outcomes: Set<HistoryOutcome> = emptySet(),
    val kinds: Set<Operation.Metadata.Kind> = emptySet(),
    /**
     * Directory path scope. When non-null, only operations with at least one affected/intended path
     * matching `path = scope OR path LIKE scope || '/%'` (with `%`/`_`/`\` properly escaped before
     * binding) are included. Matches exact directory and descendants — never sibling-prefix.
     */
    val pathScope: String? = null,
) : Parcelable {
    val isUnfiltered: Boolean
        get() = outcomes.isEmpty() && kinds.isEmpty() && pathScope == null
}

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
 *
 * Field shape note: a previous version stored a single `pathScope: String?`. The new field
 * `pathScopes: Set<String>` replaces it. kotlinx Json is configured with `ignoreUnknownKeys = true`
 * project-wide, so old session payloads silently drop the legacy field on restore (the user loses
 * any previously-set path filter for one session). No Room migration is required because
 * [HistoryFilter] is never persisted to Room — it lives only in workspace session JSON.
 */
@Parcelize
@Serializable
data class HistoryFilter(
    val outcomes: Set<HistoryOutcome> = emptySet(),
    val kinds: Set<Operation.Metadata.Kind> = emptySet(),
    /**
     * Set of directory path scopes joined with OR — entries are included when at least one of
     * their affected/intended/previous paths matches `path = scope OR path LIKE scope || '/%'`
     * (with `%`/`_`/`\` properly escaped before binding) for ANY scope in this set. Matches exact
     * directory and descendants — never sibling-prefix.
     *
     * Path strings are expected to be pre-normalized (trailing slashes stripped except root, blanks
     * filtered). Normalization happens at the entry point (ViewModel.addPathScope).
     */
    val pathScopes: Set<String> = emptySet(),
) : Parcelable {
    val isUnfiltered: Boolean
        get() = outcomes.isEmpty() && kinds.isEmpty() && pathScopes.isEmpty()
}

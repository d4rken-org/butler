package eu.darken.butler.workspace.ui.issues

import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.issue.Issue

/**
 * A rename an issue sheet asks its host to confirm.
 *
 * The dialog cannot be composed inside the sheet: it would be confined to the sheet's bounds and
 * ranked below it. The sheet emits this instead and the host renders the dialog as its sibling.
 *
 * [issueId] pins the request to the conflict it came from, so a conflict that gets replaced while
 * the dialog is open can never be resolved with the previous one's data.
 */
data class PathIssueRenameRequest(
    val issueId: Issue.Id,
    val target: Target,
    val currentName: String,
    val suggestedName: String? = null,
    /**
     * Whether the user asked to apply this to every later conflict too. The typed name is still
     * used only for this one, apply-to-all makes the operation auto-name the rest.
     * [Target.DESTINATION] never carries it, that resolution has no apply-to-all by design.
     */
    val applyToAll: Boolean = false,
) {
    enum class Target {
        /** Rename the incoming item. */
        SOURCE,

        /** Rename the item already at the destination. */
        DESTINATION,
    }

    fun toResolution(newName: String): PathActionIssue.PathAlreadyExists.Resolution = when (target) {
        Target.SOURCE -> PathActionIssue.PathAlreadyExists.Resolution.RenameSource(newName, applyToAll = applyToAll)
        Target.DESTINATION -> PathActionIssue.PathAlreadyExists.Resolution.RenameDestination(
            newName,
            applyToAll = false,
        )
    }
}

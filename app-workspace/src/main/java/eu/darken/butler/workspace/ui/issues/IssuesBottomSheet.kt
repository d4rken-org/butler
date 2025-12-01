package eu.darken.butler.workspace.ui.issues

import androidx.compose.runtime.Composable
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

@Composable
fun IssuesBottomSheet(
    issue: Issue,
    onResolution: (PathActionIssue.Resolution) -> Unit,
    onDismiss: () -> Unit,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
    ) {
        when (issue) {
            is PathActionIssue.PathAlreadyExists -> PathAlreadyExistsIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
            is PathActionIssue.InsufficientPermission -> InsufficientPermissionIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
            is PathActionIssue.InsufficientSpace -> InsufficientSpaceIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
            is PathActionIssue.UnknownError -> UnknownErrorIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
            is PathActionIssue.TrashSizeLimitExceeded -> TrashSizeLimitIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
            else -> throw IllegalArgumentException("Unknown issue type: $issue")
        }
    }
}

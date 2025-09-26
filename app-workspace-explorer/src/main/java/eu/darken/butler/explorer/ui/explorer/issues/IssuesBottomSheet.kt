package eu.darken.butler.explorer.ui.explorer.issues

import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.issue.Issue

@Composable
fun IssueBottomSheet(
    issue: Issue,
    onResolution: (PathActionIssue.Resolution) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
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
            else -> throw IllegalArgumentException("Unknown issue type: $issue")
        }
    }
}
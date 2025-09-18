package eu.darken.butler.explorer.ui.explorer.issues

import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import eu.darken.butler.common.files.operations.Issue

@Composable
fun IssueBottomSheet(
    issue: Issue,
    onResolution: (Issue.Resolution) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        when (issue) {
            is Issue.PathAlreadyExists -> PathAlreadyExistsIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
            is Issue.InsufficientPermission -> InsufficientPermissionIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
            is Issue.InsufficientSpace -> InsufficientSpaceIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
            is Issue.UnknownError -> UnknownErrorIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
        }
    }
}
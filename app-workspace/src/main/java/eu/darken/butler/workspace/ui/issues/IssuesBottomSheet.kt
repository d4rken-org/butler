package eu.darken.butler.workspace.ui.issues

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import kotlin.time.Instant

@Composable
fun IssuesBottomSheet(
    issue: Issue,
    onResolution: (PathActionIssue.Resolution) -> Unit,
    onDismiss: () -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
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
            is PathActionIssue.TrashMoveFailed -> TrashMoveFailedIssueSheet(
                issue = issue,
                onResolution = onResolution,
            )
            else -> throw IllegalArgumentException("Unknown issue type: $issue")
        }
    }
}

@Preview2
@Composable
private fun IssuesBottomSheetPreview() {
    PreviewWrapper {
        IssuesBottomSheet(
            issue = PathActionIssue.TrashSizeLimitExceeded(
                totalSize = 3L * 1024 * 1024 * 1024,
                itemCount = 5,
                trashMaxSize = 2L * 1024 * 1024 * 1024,
            ),
            onResolution = {},
            onDismiss = {},
        )
    }
}

@Preview2
@Composable
private fun IssuesBottomSheetTrashMoveFailedPreview() {
    PreviewWrapper {
        IssuesBottomSheet(
            issue = PathActionIssue.TrashMoveFailed(
                failedItems = listOf(
                    LocalPathLookup(
                        lookedUp = LocalPath.build("/storage/emulated/0/Download/file.txt"),
                        fileType = FileType.FILE,
                        size = 1024L,
                        modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000),
                        target = null,
                    ),
                ),
            ),
            onResolution = {},
            onDismiss = {},
        )
    }
}

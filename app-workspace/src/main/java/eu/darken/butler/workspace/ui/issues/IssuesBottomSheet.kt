package eu.darken.butler.workspace.ui.issues

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.pkgs.installer.AppInstallConfirmationIssue
import eu.darken.butler.workspace.R
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
    // Keyed on the issue, so a conflict that gets replaced while the rename dialog is open cannot
    // be confirmed with the previous one's name.
    var renameRequest by remember(issue.id) { mutableStateOf<PathIssueRenameRequest?>(null) }

    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        // Always visible, so a replaced conflict would otherwise inherit the previous one's
        // scroll offset instead of starting at the top.
        contentKey = issue.id,
    ) {
        key(issue.id) {
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> PathAlreadyExistsIssueSheet(
                    issue = issue,
                    onResolution = onResolution,
                    onRenameRequest = { renameRequest = it },
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
                is PathActionIssue.TrashNotSupported -> TrashNotSupportedIssueSheet(
                    issue = issue,
                    onResolution = onResolution,
                )
                is PathActionIssue.ArchivePasswordRequired -> ArchivePasswordIssueSheet(
                    issue = issue,
                    onResolution = onResolution,
                )
                // Resolved by launching an intent rather than by a resolution: the answer belongs to
                // Android's own dialog, and the operation leaves Waiting once that dialog reports.
                is AppInstallConfirmationIssue -> AppInstallConfirmationIssueSheet(
                    issue = issue,
                    onConfirmed = onDismiss,
                )
                else -> throw IllegalArgumentException("Unknown issue type: $issue")
            }
        }
    }

    // Emitted after the sheet, never inside it: a dialog composed inside would be confined to the
    // sheet's bounds and ranked below it. As a sibling it inherits the same ambient rank — never a
    // hardcoded one, because a pane-local child (the saver) needs the child overlay rank instead —
    // and PaneLayerState stacks a later same-rank layer on top, so the sheet returns to the top
    // when the dialog is dismissed.
    renameRequest?.let { request ->
        PathIssueRenameDialog(
            currentName = request.currentName,
            initialValue = request.suggestedName,
            dialogTitle = stringResource(
                when (request.target) {
                    PathIssueRenameRequest.Target.SOURCE -> R.string.workspace_issue_rename_dialog_title_new
                    PathIssueRenameRequest.Target.DESTINATION -> R.string.workspace_issue_rename_dialog_title_existing
                }
            ),
            onConfirm = { newName ->
                onResolution(request.toResolution(newName))
                renameRequest = null
            },
            onDismiss = { renameRequest = null },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun IssuesBottomSheetPreview() {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun IssuesBottomSheetTrashNotSupportedPreview() {
    IssuesBottomSheet(
        issue = PathActionIssue.TrashNotSupported(
            untrashableItems = listOf(
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun IssuesBottomSheetTrashMoveFailedPreview() {
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

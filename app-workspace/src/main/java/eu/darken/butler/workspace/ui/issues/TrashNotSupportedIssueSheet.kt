package eu.darken.butler.workspace.ui.issues

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.R
import kotlin.time.Instant

@Composable
fun TrashNotSupportedIssueSheet(
    issue: PathActionIssue.TrashNotSupported,
    onResolution: (PathActionIssue.TrashNotSupported.Resolution) -> Unit,
    modifier: Modifier = Modifier,
) {
    DeleteSkipCancelIssueBody(
        modifier = modifier,
        title = issue.title.asComposable(),
        description = issue.description.asComposable(),
        items = issue.untrashableItems,
        itemsLabelRes = R.string.workspace_issue_trash_not_supported_items_label,
        moreItemsRes = R.string.workspace_issue_trash_not_supported_more_items,
        onDeletePermanently = { onResolution(PathActionIssue.TrashNotSupported.Resolution.DeletePermanently) },
        onSkip = { onResolution(PathActionIssue.TrashNotSupported.Resolution.Skip) },
        onCancel = { onResolution(PathActionIssue.TrashNotSupported.Resolution.Cancel()) },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNotSupportedIssueSheetPreview() {
    TrashNotSupportedIssueSheet(
        issue = PathActionIssue.TrashNotSupported(
            untrashableItems = listOf(
                LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Download/file1.txt"),
                    fileType = FileType.FILE,
                    size = 1024L,
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000),
                    target = null,
                ),
                LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Download/file2.pdf"),
                    fileType = FileType.FILE,
                    size = 2048L,
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 7200000),
                    target = null,
                ),
            ),
        ),
        onResolution = {},
    )
}

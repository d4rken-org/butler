package eu.darken.butler.workspace.ui.issues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.SkipNext
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
fun TrashMoveFailedIssueSheet(
    issue: PathActionIssue.TrashMoveFailed,
    onResolution: (PathActionIssue.TrashMoveFailed.Resolution) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = issue.title.asComposable(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        Text(
            modifier = Modifier.padding(bottom = 8.dp),
            text = issue.description.asComposable(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Show first few failed items if available
        if (issue.failedItems.isNotEmpty()) {
            Text(
                text = stringResource(R.string.workspace_issue_trash_move_failed_items_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val itemsToShow = issue.failedItems.take(3)
            itemsToShow.forEach { lookup ->
                PathIssueFileComparisonCard(lookup = lookup)
            }
            if (issue.failedItems.size > 3) {
                Text(
                    text = stringResource(
                        R.string.workspace_issue_trash_move_failed_more_items,
                        issue.failedItems.size - 3,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Delete Permanently (full width, danger color)
        OutlinedButton(
            onClick = {
                onResolution(PathActionIssue.TrashMoveFailed.Resolution.DeletePermanently)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.TwoTone.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.workspace_issue_common_delete_permanently),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Skip and Cancel row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    onResolution(PathActionIssue.TrashMoveFailed.Resolution.Skip)
                },
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.workspace_issue_common_skip),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TextButton(
                onClick = {
                    onResolution(PathActionIssue.TrashMoveFailed.Resolution.Cancel())
                },
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.workspace_issue_common_cancel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun TrashMoveFailedIssueSheetPreview() {
    PreviewWrapper {
        TrashMoveFailedIssueSheet(
            issue = PathActionIssue.TrashMoveFailed(
                failedItems = listOf(
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
}

@Preview2
@Composable
private fun TrashMoveFailedIssueSheetManyItemsPreview() {
    PreviewWrapper {
        TrashMoveFailedIssueSheet(
            issue = PathActionIssue.TrashMoveFailed(
                failedItems = (1..10).map { i ->
                    LocalPathLookup(
                        lookedUp = LocalPath.build("/storage/emulated/0/Download/file$i.txt"),
                        fileType = FileType.FILE,
                        size = 1024L * i,
                        modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000L * i),
                        target = null,
                    )
                },
            ),
            onResolution = {},
        )
    }
}

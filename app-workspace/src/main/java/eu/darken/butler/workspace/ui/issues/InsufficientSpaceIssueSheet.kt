package eu.darken.butler.workspace.ui.issues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.Refresh
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
import eu.darken.butler.common.R as CommonR

@Composable
fun InsufficientSpaceIssueSheet(
    issue: PathActionIssue.InsufficientSpace,
    onResolution: (PathActionIssue.InsufficientSpace.Resolution) -> Unit,
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
            modifier = modifier.padding(bottom = 8.dp),
            text = issue.description.asComposable(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.workspace_issue_common_source_file),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathIssueFileComparisonCard(lookup = issue.source)

        Text(
            text = stringResource(R.string.workspace_issue_common_destination_file),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathIssueFileComparisonCard(path = issue.destinationPath)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    onResolution(PathActionIssue.InsufficientSpace.Resolution.Retry)
                },
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(CommonR.string.general_retry_action),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TextButton(
                onClick = {
                    onResolution(PathActionIssue.InsufficientSpace.Resolution.Cancel())
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
private fun InsufficientSpaceIssueSheetPreview() {
    PreviewWrapper {
        InsufficientSpaceIssueSheet(
            issue = PathActionIssue.InsufficientSpace(
                source = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/large_file.mp4"),
                    fileType = FileType.FILE,
                    size = 2L * 1024 * 1024 * 1024, // 2GB
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000),
                    target = null,
                ),
                destinationPath = LocalPath.build("/storage/sdcard/large_file.mp4"),
            ),
            onResolution = {},
        )
    }
}

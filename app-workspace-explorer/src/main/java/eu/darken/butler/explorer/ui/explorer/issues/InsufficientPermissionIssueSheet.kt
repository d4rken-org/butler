package eu.darken.butler.explorer.ui.explorer.issues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.SkipNext
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.R
import kotlin.time.Instant

@Composable
fun InsufficientPermissionIssueSheet(
    issue: PathActionIssue.InsufficientPermission,
    onResolution: (PathActionIssue.InsufficientPermission.Resolution) -> Unit,
    modifier: Modifier = Modifier,
) {
    var applyToAll by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.explorer_issue_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        Text(
            modifier  = modifier.padding(bottom = 8.dp),
            text = stringResource(R.string.explorer_issue_permission_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.explorer_issue_common_source_file),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathIssueFileComparisonCard(lookup = issue.destination)

        Text(
            text = stringResource(R.string.explorer_issue_common_destination_file),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathIssueFileComparisonCard(lookup = issue.destination)

        if (issue.canSkip) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .toggleable(
                        value = applyToAll,
                        onValueChange = { applyToAll = it }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(
                    checked = applyToAll,
                    onCheckedChange = null,
                )
                Text(
                    text = stringResource(R.string.explorer_issue_apply_all),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (issue.canSkip) {
                OutlinedButton(
                    onClick = {
                        onResolution(PathActionIssue.InsufficientPermission.Resolution.Skip(applyToAll))
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
                        Text(stringResource(R.string.explorer_issue_common_skip))
                    }
                }
            }

            TextButton(
                onClick = {
                    onResolution(PathActionIssue.InsufficientPermission.Resolution.Cancel())
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
                    Text(stringResource(R.string.explorer_issue_common_cancel))
                }
            }
        }
    }
}

@Preview2
@Composable
private fun InsufficientPermissionConflictSheetPreview() {
    PreviewWrapper {
        InsufficientPermissionIssueSheet(
            issue = PathActionIssue.InsufficientPermission(
                source = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Download/document.pdf"),
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 5, // 5MB
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000), // 1 hour ago
                    target = null,
                ),
                destination = LocalPathLookup(
                    lookedUp = LocalPath.build("/system/protected/document.pdf"),
                    fileType = FileType.FILE,
                    size = 0,
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    target = null,
                ),
                canSkip = true,
            ),
            onResolution = {},
        )
    }
}
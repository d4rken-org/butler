package eu.darken.butler.explorer.ui.explorer.conflicts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.operations.conflicts.Conflict
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Composable
fun InsufficientPermissionConflictSheet(
    conflict: Conflict.InsufficientPermission,
    onResolution: (Conflict.InsufficientPermission.Resolution) -> Unit,
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
            text = stringResource(R.string.explorer_conflict_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        Text(
            modifier  = modifier.padding(bottom = 8.dp),
            text = stringResource(R.string.explorer_conflict_permission_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.explorer_conflict_common_source_file),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathConflictFileComparisonCard(lookup = conflict.source)

        Text(
            text = stringResource(R.string.explorer_conflict_common_destination_file),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PathConflictFileComparisonCard(lookup = conflict.destination)

        if (conflict.canSkip) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = applyToAll,
                    onCheckedChange = { applyToAll = it },
                )
                Text(
                    text = stringResource(R.string.explorer_conflict_apply_all),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (conflict.canSkip) {
                OutlinedButton(
                    onClick = {
                        onResolution(Conflict.InsufficientPermission.Resolution.Skip(applyToAll))
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
                        Text(stringResource(R.string.explorer_conflict_common_skip))
                    }
                }
            }

            TextButton(
                onClick = {
                    onResolution(Conflict.InsufficientPermission.Resolution.Cancel)
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
                    Text(stringResource(R.string.explorer_conflict_common_cancel))
                }
            }
        }
    }
}

@Preview2
@Composable
private fun InsufficientPermissionConflictSheetPreview() {
    PreviewWrapper {
        InsufficientPermissionConflictSheet(
            conflict = Conflict.InsufficientPermission(
                conflictId = Uuid.random(),
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
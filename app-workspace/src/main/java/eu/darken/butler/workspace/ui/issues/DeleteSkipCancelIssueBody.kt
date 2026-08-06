package eu.darken.butler.workspace.ui.issues

import androidx.annotation.StringRes
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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.R
import kotlin.time.Instant

/**
 * Shared layout for partial-outcome delete issues, where the user picks between deleting the
 * listed items permanently, skipping them or cancelling the whole operation.
 */
@Composable
internal fun DeleteSkipCancelIssueBody(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    items: List<APathLookup<out APath<*>>>,
    @StringRes itemsLabelRes: Int,
    @StringRes moreItemsRes: Int,
    onDeletePermanently: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        HorizontalDivider()

        Text(
            modifier = Modifier.padding(bottom = 8.dp),
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Show first few affected items if available
        if (items.isNotEmpty()) {
            Text(
                text = stringResource(itemsLabelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val itemsToShow = items.take(3)
            itemsToShow.forEach { lookup ->
                PathIssueFileComparisonCard(lookup = lookup)
            }
            if (items.size > 3) {
                Text(
                    text = stringResource(moreItemsRes, items.size - 3),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Delete Permanently (full width, danger color)
        OutlinedButton(
            onClick = onDeletePermanently,
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

        // Cancel and Skip row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onCancel,
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

            OutlinedButton(
                onClick = onSkip,
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
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeleteSkipCancelIssueBodyPreview() {
    DeleteSkipCancelIssueBody(
        title = "Trash not supported",
        description = "2 items are on storage that doesn't support the trash.",
        items = listOf(
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
        itemsLabelRes = R.string.workspace_issue_trash_not_supported_items_label,
        moreItemsRes = R.string.workspace_issue_trash_not_supported_more_items,
        onDeletePermanently = {},
        onSkip = {},
        onCancel = {},
    )
}

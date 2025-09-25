package eu.darken.butler.workspace.ui.operations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import kotlin.time.Clock

@Composable
fun OperationEntryRow(
    operation: OperationDisplay,
    onRowClick: () -> Unit,
    modifier: Modifier = Modifier,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onRowClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = operation.icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (operation.state) {
                    is OperationDisplay.State.Running -> operation.state.primaryProgress.primary.asComposable()
                    else -> operation.title.asComposable()
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondaryText = when (operation.state) {
                is OperationDisplay.State.Running -> operation.state.primaryProgress.secondary.asComposable()
                else -> operation.description?.asComposable()
            }
            if (secondaryText != null) {
                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val progressData = (operation.state as? OperationDisplay.State.Running)?.primaryProgress
            progressData?.let { progressData ->
                Spacer(modifier = Modifier.height(2.dp))

                val count = progressData.count
                when (count) {
                    is Progress.Count.Percent,
                    is Progress.Count.Counter,
                    is Progress.Count.Size -> {
                        val fraction = if (count.max > 0) {
                            count.current / count.max.toFloat()
                        } else 0f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = count.displayValue.asComposable(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is Progress.Count.Indeterminate -> {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is Progress.Count.None -> {
                        // No progress indicator
                    }
                }
            }
        }

        // Show action button for active operations if they can be cancelled, otherwise show state indicator
        if (operation.state is OperationDisplay.State.Running && onActionClick != null) {
            IconButton(
                onClick = onActionClick,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Cancel,
                    contentDescription = stringResource(R.string.operations_cancel_operation),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            OperationStateIndicator(
                state = operation.state,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Preview2
@Composable
private fun OperationEntryRowCounterPreview() {
    PreviewWrapper {
        OperationEntryRow(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Deleting files".toCaString(),
                description = "Removing selected items".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Running(
                    primaryProgress = Progress.Data(
                        primary = "Deleting files".toCaString(),
                        secondary = "Processing item 3 of 4".toCaString(),
                        count = Progress.Count.Counter(3, 4)
                    )
                ),
                startedAt = Clock.System.now(),
            ),
            onRowClick = {},
            onActionClick = {},
        )
    }
}

@Preview2
@Composable
private fun OperationEntryRowPercentPreview() {
    PreviewWrapper {
        OperationEntryRow(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Copying files".toCaString(),
                description = "Copying to backup folder".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Running(
                    primaryProgress = Progress.Data(
                        primary = "Copying files".toCaString(),
                        secondary = "Processing...".toCaString(),
                        count = Progress.Count.Percent(75, 100)
                    )
                ),
                startedAt = Clock.System.now(),
            ),
            onRowClick = {},
            onActionClick = {},
        )
    }
}

@Preview2
@Composable
private fun OperationEntryRowSizePreview() {
    PreviewWrapper {
        OperationEntryRow(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Moving large files".toCaString(),
                description = "Transferring video files".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Running(
                    primaryProgress = Progress.Data(
                        primary = "Moving files".toCaString(),
                        secondary = "Transferring data...".toCaString(),
                        count = Progress.Count.Size(1024 * 1024 * 250, 1024 * 1024 * 500) // 250MB/500MB
                    )
                ),
                startedAt = Clock.System.now(),
            ),
            onRowClick = {},
            onActionClick = {},
        )
    }
}

@Preview2
@Composable
private fun OperationEntryRowIndeterminatePreview() {
    PreviewWrapper {
        OperationEntryRow(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Scanning files".toCaString(),
                description = "Analyzing directory structure".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Running(
                    primaryProgress = Progress.Data(
                        primary = "Scanning".toCaString(),
                        secondary = "Please wait...".toCaString(),
                        count = Progress.Count.Indeterminate()
                    )
                ),
                startedAt = Clock.System.now(),
            ),
            onRowClick = {},
            onActionClick = {},
        )
    }
}

@Preview2
@Composable
private fun OperationEntryRowCompletedPreview() {
    PreviewWrapper {
        OperationEntryRow(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Delete operation".toCaString(),
                description = "Successfully deleted 5 files".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Completed(
                    summary = "Successfully completed".toCaString(),
                    completedAt = Clock.System.now()
                ),
                startedAt = Clock.System.now(),
            ),
            onRowClick = {},
            onActionClick = {},
        )
    }
}
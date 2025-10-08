package eu.darken.butler.workspace.ui.operations.bar

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
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.HourglassEmpty
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Pause
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material3.Icon
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
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Composable
fun OperationEntryRow(
    operation: OperationDisplay,
    onRowClick: () -> Unit,
    modifier: Modifier = Modifier,
    onActionClick: (() -> Unit)? = null,
    showSecondaryText: Boolean = true,
    isBarExpanded: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable { onRowClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Use small icon layout when bar is expanded
        val useSmallIconLayout = isBarExpanded

        if (useSmallIconLayout) {
            // Expanded layout with small icons for all operations
            Column(modifier = Modifier.weight(1f)) {
                // Row 1: Operation icon + Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = operation.icon,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = operation.title.asComposable(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Row 2: Icon + secondary text (result icon for finished ops, info icon for running ops)
                if (showSecondaryText) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Choose icon based on operation state
                        val (rowIcon, rowTint) = when (operation.state) {
                            is OperationDisplay.State.Completed -> Icons.TwoTone.CheckCircle to MaterialTheme.colorScheme.onSecondaryContainer
                            is OperationDisplay.State.Failed -> Icons.TwoTone.Error to MaterialTheme.colorScheme.error
                            is OperationDisplay.State.Cancelled -> Icons.TwoTone.Cancel to MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                alpha = 0.7f
                            )
                            is OperationDisplay.State.Running -> Icons.TwoTone.Info to MaterialTheme.colorScheme.onSecondaryContainer
                            is OperationDisplay.State.Waiting -> Icons.TwoTone.Pause to MaterialTheme.colorScheme.tertiary
                            else -> Icons.TwoTone.Info to MaterialTheme.colorScheme.onSecondaryContainer
                        }

                        Icon(
                            imageVector = rowIcon,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = rowTint,
                        )
                        Spacer(modifier = Modifier.width(6.dp))

                        val secondaryText = when (operation.state) {
                            is OperationDisplay.State.Running -> operation.state.primaryProgress.secondary.asComposable()
                            is OperationDisplay.State.Completed -> operation.state.summary.asComposable()
                            is OperationDisplay.State.Failed -> operation.state.summary.asComposable()
                            is OperationDisplay.State.Waiting -> operation.state.reason.asComposable()
                            else -> operation.description.asComposable()
                        }

                        Text(
                            text = secondaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Row 3: Duration + state (for finished ops) or progress bar (for running/waiting ops)
                when (operation.state) {
                    is OperationDisplay.State.Completed,
                    is OperationDisplay.State.Failed,
                    is OperationDisplay.State.Cancelled -> {
                        // Duration row for finished operations
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            val (completedAt, stateStringRes) = when (operation.state) {
                                is OperationDisplay.State.Completed -> operation.state.completedAt to R.string.operations_state_successful
                                is OperationDisplay.State.Failed -> operation.state.completedAt to R.string.operations_state_failed
                                is OperationDisplay.State.Cancelled -> operation.state.completedAt to R.string.operations_state_cancelled
                                else -> null to null
                            }

                            val durationText = completedAt?.let {
                                val duration = it - operation.startedAt
                                val formatted = when {
                                    duration.inWholeSeconds < 1 -> "${duration.inWholeMilliseconds}ms"
                                    duration.inWholeSeconds < 60 -> String.format(
                                        "%.1fs",
                                        duration.inWholeMilliseconds / 1000.0
                                    )
                                    else -> {
                                        val minutes = duration.inWholeMinutes
                                        val seconds = duration.inWholeSeconds % 60
                                        "${minutes}m ${seconds}s"
                                    }
                                }
                                val stateLabel = stateStringRes?.let { stringResource(it) } ?: ""
                                "$formatted • $stateLabel"
                            } ?: ""

                            Text(
                                text = durationText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    is OperationDisplay.State.Running -> {
                        // Progress bar for running operations
                        val progressData = operation.state.primaryProgress
                        // Add spacing
                        Spacer(modifier = Modifier.height(2.dp))

                        when (val count = progressData.count) {
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
                                    Icon(
                                        imageVector = Icons.TwoTone.HourglassEmpty,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))

                                    LinearProgressIndicator(
                                        progress = { fraction },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = count.displayValue.asComposable(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            is Progress.Count.Indeterminate -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.TwoTone.HourglassEmpty,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))

                                    LinearProgressIndicator(
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            is Progress.Count.None -> {
                                // No progress indicator
                            }
                        }
                    }
                    is OperationDisplay.State.Waiting -> {
                        // Indeterminate progress bar for waiting operations
                        Spacer(modifier = Modifier.height(2.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.HourglassEmpty,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            )
                            Spacer(modifier = Modifier.width(6.dp))

                            LinearProgressIndicator(
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    else -> {}
                }
            }
        } else {
            // Standard layout for running operations or collapsed view
            Icon(
                imageVector = operation.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (operation.state) {
                        is OperationDisplay.State.Running -> operation.state.primaryProgress.primary.asComposable()
                        else -> operation.title.asComposable()
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (showSecondaryText) {
                    val secondaryText = when (operation.state) {
                        is OperationDisplay.State.Running -> operation.state.primaryProgress.secondary.asComposable()
                        is OperationDisplay.State.Completed -> operation.state.summary.asComposable()
                        is OperationDisplay.State.Waiting -> operation.state.reason.asComposable()
                        else -> operation.description.asComposable()
                    }
                    Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Progress bar for running or waiting operations
                val progressData = (operation.state as? OperationDisplay.State.Running)?.primaryProgress
                val isWaiting = operation.state is OperationDisplay.State.Waiting

                if (progressData != null || isWaiting) {
                    // Add spacing
                    if (!showSecondaryText) {
                        Spacer(modifier = Modifier.height(4.dp))
                    } else {
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }

                progressData?.let { progressData ->
                    when (val count = progressData.count) {
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
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
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

                // Show indeterminate progress for waiting operations
                if (isWaiting) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        OperationActionIndicator(
            state = operation.state,
            modifier = Modifier.size(24.dp),
            onAction = if (operation.state is OperationDisplay.State.Running) onActionClick else null
        )
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
            isBarExpanded = false,
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
            isBarExpanded = false,
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
            isBarExpanded = false,
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
            isBarExpanded = false,
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
                    summary = "Deleted 5 items".toCaString(),
                    completedAt = Clock.System.now() + 2.5.seconds,
                    report = object : Operation.Report {
                        override val summary = "Deleted 5 items".toCaString()
                        override val affectedPaths = emptyList<Operation.Report.PathChange>()
                    }
                ),
                startedAt = Clock.System.now(),
            ),
            onRowClick = {},
            onActionClick = {},
            showSecondaryText = true,
            isBarExpanded = true,
        )
    }
}

@Preview2
@Composable
private fun OperationEntryRowFailedPreview() {
    PreviewWrapper {
        OperationEntryRow(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Copy operation".toCaString(),
                description = "Failed to copy files".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Failed(
                    summary = "Permission denied".toCaString(),
                    completedAt = Clock.System.now() + 1.2.seconds,
                    report = null,
                ),
                startedAt = Clock.System.now(),
            ),
            onRowClick = {},
            onActionClick = {},
            showSecondaryText = true,
            isBarExpanded = true,
        )
    }
}

@Preview2
@Composable
private fun OperationEntryRowCancelledPreview() {
    PreviewWrapper {
        OperationEntryRow(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Move operation".toCaString(),
                description = "Cancelled by user".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Cancelled(
                    completedAt = Clock.System.now() + 0.8.seconds,
                    report = null,
                ),
                startedAt = Clock.System.now(),
            ),
            onRowClick = {},
            onActionClick = {},
            showSecondaryText = true,
            isBarExpanded = true,
        )
    }
}
package eu.darken.butler.workspace.ui.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationDetailsSheet(
    operation: OperationDisplay,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    onCopyError: (() -> Unit)? = null,
    onGoToFolder: (() -> Unit)? = null,
) {
    val isInPreview = LocalInspectionMode.current

    if (isInPreview) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            OperationDetailsContent(
                operation = operation,
                onCancel = onCancel,
                onCopyError = onCopyError,
                onGoToFolder = onGoToFolder,
            )
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
        ) {
            OperationDetailsContent(
                operation = operation,
                onCancel = onCancel,
                onCopyError = onCopyError,
                onGoToFolder = onGoToFolder,
            )
        }
    }
}

@Composable
private fun OperationDetailsContent(
    operation: OperationDisplay,
    onCancel: (() -> Unit)? = null,
    onCopyError: (() -> Unit)? = null,
    onGoToFolder: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header Section
        OperationDetailsHeader(
            operation = operation
        )

        // Overview Section
        OperationOverviewSection(
            operation = operation,
        )

        // Check if we have middle content sections
        val hasMiddleContent = operation.state is OperationDisplay.State.Running ||
            operation.state is OperationDisplay.State.Failed

        if (hasMiddleContent) {
            // Progress Section (for running operations)
            if (operation.state is OperationDisplay.State.Running) {
                OperationCombinedProgressSection(
                    primaryProgress = operation.state.primaryProgress,
                    secondaryProgress = operation.state.secondaryProgress,
                )
            }

            // Error Section (for failed operations)
            if (operation.state is OperationDisplay.State.Failed) {
                OperationErrorSection(
                    state = operation.state,
                )
            }
        }

        // Actions Section
        OperationActionsSection(
            operation = operation,
            onCancel = onCancel,
            onCopyError = onCopyError,
            onGoToFolder = onGoToFolder,
        )
    }
}

@Composable
private fun OperationDetailsHeader(
    operation: OperationDisplay,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = operation.icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = operation.title.asComposable(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            operation.description?.let { description ->
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = description.asComposable(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


@Composable
private fun OperationCombinedProgressSection(
    primaryProgress: Progress.Data,
    secondaryProgress: Progress.Data?,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.operations_details_progress),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Primary Progress
                OperationProgressDisplay(
                    progressData = primaryProgress,
                    isPrimary = true
                )

                // Secondary Progress (if exists)
                secondaryProgress?.let { secondary ->
                    OperationProgressDisplay(
                        progressData = secondary,
                        isPrimary = false
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationProgressDisplay(
    progressData: Progress.Data,
    isPrimary: Boolean
) {
    val count = progressData.count
    when (count) {
        is Progress.Count.Percent,
        is Progress.Count.Counter,
        is Progress.Count.Size -> {
            // Primary text and percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = progressData.primary.asComposable(),
                    style = if (isPrimary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = if (isPrimary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = count.displayValue.asComposable(),
                    style = if (isPrimary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                    color = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Progress bar
            LinearProgressIndicator(
                progress = { count.percentage },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!isPrimary) Modifier.height(2.dp) else Modifier
                    ),
            )

            // Secondary text
            if (progressData.secondary != CaString.EMPTY) {
                Text(
                    text = progressData.secondary.asComposable(),
                    style = if (isPrimary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is Progress.Count.Indeterminate -> {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (!isPrimary) Modifier.height(2.dp) else Modifier
                    ),
            )
            Text(
                text = progressData.primary.asComposable(),
                style = if (isPrimary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = if (isPrimary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is Progress.Count.None -> {
            Text(
                text = progressData.primary.asComposable(),
                style = if (isPrimary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = if (isPrimary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
private fun OperationErrorSection(
    state: OperationDisplay.State.Failed,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.operations_details_error),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = state.summary.asComposable(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun OperationActionsSection(
    operation: OperationDisplay,
    onCancel: (() -> Unit)? = null,
    onCopyError: (() -> Unit)? = null,
    onGoToFolder: (() -> Unit)? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.operations_details_actions),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (operation.state) {
                is OperationDisplay.State.Running -> {
                    if (onCancel != null) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.operations_cancel_operation))
                        }
                    }
                }
                is OperationDisplay.State.Completed -> {
                    if (onGoToFolder != null) {
                        OutlinedButton(
                            onClick = onGoToFolder,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.operations_details_go_to_folder))
                        }
                    }
                }
                is OperationDisplay.State.Failed -> {
                    if (onCopyError != null) {
                        OutlinedButton(
                            onClick = onCopyError,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.operations_details_copy_error))
                        }
                    }
                }
                else -> {
                    // No specific actions for other states
                }
            }
        }
    }
}

@Composable
private fun OperationOverviewSection(
    operation: OperationDisplay,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.operations_details_overview),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Status row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OperationStateIndicator(
                            state = operation.state,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when (operation.state) {
                                is OperationDisplay.State.Queued -> stringResource(R.string.operations_state_queued)
                                is OperationDisplay.State.Running -> stringResource(R.string.operations_state_running)
                                is OperationDisplay.State.Waiting -> stringResource(R.string.operations_state_waiting)
                                is OperationDisplay.State.Completed -> stringResource(R.string.operations_state_completed)
                                is OperationDisplay.State.Failed -> stringResource(R.string.operations_state_failed)
                                is OperationDisplay.State.Cancelled -> stringResource(R.string.operations_state_cancelled)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Timing row - show different info based on operation state
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (operation.state) {
                            is OperationDisplay.State.Completed,
                            is OperationDisplay.State.Failed,
                            is OperationDisplay.State.Cancelled -> "Completed"
                            else -> stringResource(R.string.operations_details_started_at)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when (operation.state) {
                            is OperationDisplay.State.Completed -> formatRelativeTime(operation.state.completedAt)
                            is OperationDisplay.State.Failed -> formatRelativeTime(operation.state.completedAt)
                            is OperationDisplay.State.Cancelled -> formatRelativeTime(operation.state.completedAt)
                            else -> formatRelativeTime(operation.startedAt)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Duration row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.operations_details_duration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when (operation.state) {
                            is OperationDisplay.State.Queued -> "Not started"
                            is OperationDisplay.State.Running,
                            is OperationDisplay.State.Waiting -> formatDuration(operation.startedAt)
                            is OperationDisplay.State.Completed -> formatFinalDuration(
                                operation.startedAt,
                                operation.state.completedAt
                            )
                            is OperationDisplay.State.Failed -> formatFinalDuration(
                                operation.startedAt,
                                operation.state.completedAt
                            )
                            is OperationDisplay.State.Cancelled -> formatFinalDuration(
                                operation.startedAt,
                                operation.state.completedAt
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

// Helper functions
private fun formatRelativeTime(instant: kotlin.time.Instant): String {
    val duration = Clock.System.now() - instant
    return when {
        duration.inWholeMinutes < 1 -> "Just now"
        duration.inWholeMinutes < 60 -> "${duration.inWholeMinutes}m ago"
        duration.inWholeHours < 24 -> "${duration.inWholeHours}h ago"
        else -> "${duration.inWholeDays}d ago"
    }
}

private fun formatDuration(startedAt: kotlin.time.Instant): String {
    val duration = Clock.System.now() - startedAt
    return when {
        duration.inWholeMinutes < 1 -> "${duration.inWholeSeconds}s"
        duration.inWholeHours < 1 -> "${duration.inWholeMinutes}m ${duration.inWholeSeconds % 60}s"
        else -> "${duration.inWholeHours}h ${duration.inWholeMinutes % 60}m"
    }
}

private fun formatFinalDuration(startedAt: kotlin.time.Instant, completedAt: kotlin.time.Instant): String {
    val duration = completedAt - startedAt
    return when {
        duration.inWholeMinutes < 1 -> "${duration.inWholeSeconds}s"
        duration.inWholeHours < 1 -> "${duration.inWholeMinutes}m ${duration.inWholeSeconds % 60}s"
        else -> "${duration.inWholeHours}h ${duration.inWholeMinutes % 60}m"
    }
}

@Preview2
@Composable
private fun OperationDetailsSheetRunningPreview() {
    PreviewWrapper {
        OperationDetailsSheet(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Deleting files".toCaString(),
                description = "Removing selected items".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Running(
                    primaryProgress = Progress.Data(
                        primary = "5/9 files copied".toCaString(),
                        secondary = "Processing item 5 of 9".toCaString(),
                        count = Progress.Count.Counter(5, 9)
                    ),
                    secondaryProgress = Progress.Data(
                        primary = "Copying movie.avi (50MB/s)".toCaString(),
                        secondary = "200MB of 1.2GB".toCaString(),
                        count = Progress.Count.Size(200L * 1024L * 1024L, 1200L * 1024L * 1024L)
                    )
                ),
                startedAt = Clock.System.now() - 2.minutes,
            ),
            onDismiss = {},
            onCancel = {},
        )
    }
}

@Preview2
@Composable
private fun OperationDetailsSheetFailedPreview() {
    PreviewWrapper {
        OperationDetailsSheet(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Copy operation".toCaString(),
                description = "Failed to copy files".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Failed(
                    summary = "Insufficient space".toCaString(),
                    completedAt = Clock.System.now()
                ),
                startedAt = Clock.System.now() - 5.minutes,
            ),
            onDismiss = {},
            onCopyError = {},
        )
    }
}
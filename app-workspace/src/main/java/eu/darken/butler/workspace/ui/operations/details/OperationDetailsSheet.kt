package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.formatDuration
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.OperationActionIndicator
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
            )
        }
    }
}

@Composable
private fun OperationDetailsContent(
    operation: OperationDisplay,
    onCancel: (() -> Unit)? = null,
    onCopyError: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header Section
        OperationDetailsHeader(
            operation = operation
        )

        Spacer(modifier = Modifier.height(1.dp))

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

        // Affected Files Section (for completed operations with affected paths)
        val affectedPaths = when (operation.state) {
            is OperationDisplay.State.Completed -> operation.state.report.affectedPaths
            is OperationDisplay.State.Failed -> operation.state.report?.affectedPaths ?: emptyList()
            is OperationDisplay.State.Cancelled -> operation.state.report?.affectedPaths ?: emptyList()
            else -> emptyList()
        }

        if (affectedPaths.isNotEmpty()) {
            OperationAffectedFilesSection(
                affectedPaths = affectedPaths,
            )
        }

        // Actions Section - only show if there are available actions
        val hasActions = when (operation.state) {
            is OperationDisplay.State.Running -> onCancel != null
            is OperationDisplay.State.Failed -> onCopyError != null
            else -> false
        }

        if (hasActions) {
            OperationActionsSection(
                operation = operation,
                onCancel = onCancel,
                onCopyError = onCopyError,
            )
        }
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
    // Single unified card for all progress
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Section title
            Text(
                text = stringResource(R.string.operations_details_progress).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // Primary Progress
            OperationProgressDisplay(
                progressData = primaryProgress,
                isPrimary = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Divider and Secondary Progress (if exists)
            secondaryProgress?.let { secondary ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                OperationProgressDisplay(
                    progressData = secondary,
                    isPrimary = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun OperationProgressDisplay(
    progressData: Progress.Data,
    isPrimary: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (isPrimary) 8.dp else 6.dp)
    ) {
        when (val count = progressData.count) {
            is Progress.Count.Percent,
            is Progress.Count.Counter,
            is Progress.Count.Size -> {
                // Header with title and percentage
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = progressData.primary.asComposable(),
                        style = if (isPrimary) {
                            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = if (isPrimary) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                    )
                    Text(
                        text = count.displayValue.asComposable(),
                        style = if (isPrimary) {
                            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = if (isPrimary) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                    )
                }

                // Progress bar with enhanced styling
                LinearProgressIndicator(
                    progress = { count.percentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isPrimary) {
                                Modifier.height(8.dp)
                            } else {
                                Modifier.height(3.dp)
                            }
                        ),
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    },
                    trackColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    },
                )

                // Secondary text
                if (progressData.secondary != CaString.EMPTY) {
                    Text(
                        text = progressData.secondary.asComposable(),
                        style = if (isPrimary) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (isPrimary) 0.7f else 0.6f
                        )
                    )
                }
            }
            is Progress.Count.Indeterminate -> {
                Text(
                    text = progressData.primary.asComposable(),
                    style = if (isPrimary) {
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isPrimary) {
                                Modifier.height(8.dp)
                            } else {
                                Modifier.height(3.dp)
                            }
                        ),
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    },
                    trackColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    },
                )
            }
            is Progress.Count.None -> {
                Text(
                    text = progressData.primary.asComposable(),
                    style = if (isPrimary) {
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                )
            }
        }
    }
}


@Composable
private fun OperationErrorSection(
    state: OperationDisplay.State.Failed,
) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Section title
            Text(
                text = stringResource(R.string.operations_details_error).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
            )

            Text(
                text = state.summary.asComposable(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun OperationActionsSection(
    operation: OperationDisplay,
    onCancel: (() -> Unit)? = null,
    onCopyError: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Section title
            Text(
                text = stringResource(R.string.operations_details_actions).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
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
}

@Composable
private fun OperationOverviewSection(
    operation: OperationDisplay,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Section title
            Text(
                text = stringResource(R.string.operations_details_overview).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Status row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.operations_details_status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OperationActionIndicator(
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
                            is OperationDisplay.State.Waiting -> formatDuration(Clock.System.now() - operation.startedAt)
                            is OperationDisplay.State.Completed -> formatDuration(operation.state.completedAt - operation.startedAt)
                            is OperationDisplay.State.Failed -> formatDuration(operation.state.completedAt - operation.startedAt)
                            is OperationDisplay.State.Cancelled -> formatDuration(operation.state.completedAt - operation.startedAt)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Result/Summary row (for completed/failed/cancelled operations with reports)
                val reportSummary = when (operation.state) {
                    is OperationDisplay.State.Completed -> operation.state.report.summary
                    is OperationDisplay.State.Failed -> operation.state.report?.summary
                    is OperationDisplay.State.Cancelled -> operation.state.report?.summary
                    else -> null
                }

                if (reportSummary != null) {
                    // Add spacing before result section
                    Spacer(modifier = Modifier.height(4.dp))

                    // Result label
                    Text(
                        text = stringResource(R.string.operations_details_result),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Result text with full width
                    Text(
                        text = reportSummary.asComposable(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationAffectedFilesSection(
    affectedPaths: Collection<Operation.Report.PathChange>,
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Clickable section title with expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.operations_details_affected_paths_with_count,
                        affectedPaths.size
                    ).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Icon(
                    imageVector = if (isExpanded) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Only show divider when expanded
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Expandable file list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                    ) {
                        items(affectedPaths.toList()) { pathChange ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = when (pathChange.change) {
                                        Operation.Report.PathChange.Change.ADDED -> stringResource(R.string.operations_details_path_added_marker)
                                        Operation.Report.PathChange.Change.REMOVED -> stringResource(R.string.operations_details_path_removed_marker)
                                        Operation.Report.PathChange.Change.MODIFIED -> stringResource(R.string.operations_details_path_modified_marker)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = when (pathChange.change) {
                                        Operation.Report.PathChange.Change.ADDED -> MaterialTheme.colorScheme.primary
                                        Operation.Report.PathChange.Change.REMOVED -> MaterialTheme.colorScheme.error
                                        Operation.Report.PathChange.Change.MODIFIED -> MaterialTheme.colorScheme.secondary
                                    },
                                    modifier = Modifier.width(16.dp)
                                )

                                Text(
                                    text = pathChange.path.userReadablePath.asComposable(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper functions
private fun createMockReport(
    affectedPaths: List<Operation.Report.PathChange> = emptyList()
): Operation.Report = object : Operation.Report {
    override val summary = "Completed successfully".toCaString()
    override val affectedPaths = affectedPaths
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
                        primary = "Deleting selected items".toCaString(),
                        secondary = "Processing folder contents".toCaString(),
                        count = Progress.Count.Counter(3, 5)
                    ),
                    secondaryProgress = Progress.Data(
                        primary = "Items in Documents folder".toCaString(),
                        secondary = "/storage/emulated/0/Documents/report.pdf".toCaString(),
                        count = Progress.Count.Counter(12, 45)
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
                    completedAt = Clock.System.now(),
                    report = createMockReport()
                ),
                startedAt = Clock.System.now() - 5.minutes,
            ),
            onDismiss = {},
            onCopyError = {},
        )
    }
}

@Preview2
@Composable
private fun OperationDetailsSheetCompletedWithFilesPreview() {
    PreviewWrapper {
        OperationDetailsSheet(
            operation = OperationDisplay(
                id = Operation.Id(),
                title = "Delete operation".toCaString(),
                description = "Completed successfully".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Completed(
                    summary = "Successfully deleted 15 items".toCaString(),
                    completedAt = Clock.System.now(),
                    report = createMockReport(
                        affectedPaths = listOf(
                            Operation.Report.PathChange(
                                path = RawPath.build("/home", "user", "documents", "file1.txt"),
                                change = Operation.Report.PathChange.Change.REMOVED
                            ),
                            Operation.Report.PathChange(
                                path = RawPath.build("/home", "user", "documents", "file2.pdf"),
                                change = Operation.Report.PathChange.Change.REMOVED
                            ),
                            Operation.Report.PathChange(
                                path = RawPath.build("/home", "user", "downloads", "temp"),
                                change = Operation.Report.PathChange.Change.REMOVED
                            ),
                            Operation.Report.PathChange(
                                path = RawPath.build("/home", "user", "backup", "copy1.txt"),
                                change = Operation.Report.PathChange.Change.ADDED
                            ),
                            Operation.Report.PathChange(
                                path = RawPath.build("/home", "user", "config.xml"),
                                change = Operation.Report.PathChange.Change.MODIFIED
                            ),
                            Operation.Report.PathChange(
                                path = RawPath.build("/home", "user", "old", "archive.zip"),
                                change = Operation.Report.PathChange.Change.REMOVED
                            ),
                            Operation.Report.PathChange(
                                path = RawPath.build(
                                    "/storage",
                                    "emulated",
                                    "0",
                                    "Android",
                                    "data",
                                    "com.example.app",
                                    "cache",
                                    "temp.log"
                                ),
                                change = Operation.Report.PathChange.Change.REMOVED
                            ),
                            Operation.Report.PathChange(
                                path = RawPath.build("/sdcard", "Pictures", "Screenshots", "screenshot_1.png"),
                                change = Operation.Report.PathChange.Change.REMOVED
                            ),
                        )
                    )
                ),
                startedAt = Clock.System.now() - 3.minutes,
            ),
            onDismiss = {},
        )
    }
}
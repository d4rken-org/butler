package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.formatDuration
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.OperationActionIndicator
import kotlin.time.Clock

@Composable
internal fun OperationOverviewSection(
    operation: OperationDisplay,
) {
    var isExpanded by remember { mutableStateOf(true) }

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
                    text = stringResource(R.string.operations_details_overview).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Icon(
                    imageVector = if (isExpanded) {
                        Icons.TwoTone.ExpandLess
                    } else {
                        Icons.TwoTone.ExpandMore
                    },
                    contentDescription = if (isExpanded) {
                        "Collapse overview"
                    } else {
                        "Expand overview"
                    },
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Content
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

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
                                        is OperationDisplay.State.Completed -> stringResource(R.string.operations_state_successful)
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
                                    is OperationDisplay.State.Cancelled -> stringResource(R.string.operations_details_completed_at)
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
                                    is OperationDisplay.State.Queued -> stringResource(R.string.operations_details_duration_not_started)
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
    }
}

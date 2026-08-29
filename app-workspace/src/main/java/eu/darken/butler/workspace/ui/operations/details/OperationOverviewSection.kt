package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material.icons.twotone.Handyman
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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.InfoBlock
import eu.darken.butler.common.compose.InfoEntry
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.compose.groupInfoEntries
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.formatDuration
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.bar.operationStateVisuals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Below this the two-column grid leaves ~48dp per column; every entry goes full width instead. */
internal val OverviewPairingMinWidth = 240.dp

/**
 * Result is never paired and never capped: a move/copy summary concatenates up to four plural
 * segments, and a two-line cap would hide the tail with no way to read it. The destination is
 * absent for operations that have none (delete, create, restore).
 */
internal fun buildOverviewEntries(
    statusLabel: String,
    statusValue: String,
    timeLabel: String,
    timeValue: String,
    durationLabel: String,
    durationValue: String,
    destinationLabel: String?,
    destinationValue: String?,
    resultLabel: String,
    resultValue: String?,
): List<InfoEntry> = buildList {
    add(InfoEntry(label = statusLabel, value = statusValue, pairable = true))
    add(InfoEntry(label = timeLabel, value = timeValue, pairable = true))
    add(InfoEntry(label = durationLabel, value = durationValue, pairable = true))
    if (destinationLabel != null && destinationValue != null) {
        add(
            InfoEntry(
                label = destinationLabel,
                value = destinationValue,
                pairable = false,
                valueStyle = InfoEntry.ValueStyle.PATH,
            ),
        )
    }
    if (resultValue != null) {
        add(
            InfoEntry(
                label = resultLabel,
                value = resultValue,
                pairable = false,
                valueMaxLines = Int.MAX_VALUE,
            ),
        )
    }
}

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

                    OperationOverviewGrid(operation = operation)
                }
            }
        }
    }
}

@Composable
private fun OperationOverviewGrid(
    modifier: Modifier = Modifier,
    operation: OperationDisplay,
) {
    val reportSummary = when (operation.state) {
        is OperationDisplay.State.Completed -> operation.state.report?.summary
        is OperationDisplay.State.Failed -> operation.state.report?.summary
        is OperationDisplay.State.Cancelled -> operation.state.report?.summary
        else -> null
    }

    val destination = operation.pathPlan?.destination

    val baseEntries = buildOverviewEntries(
        statusLabel = stringResource(R.string.operations_details_status),
        statusValue = when (operation.state) {
            is OperationDisplay.State.Queued -> stringResource(R.string.operations_state_queued)
            is OperationDisplay.State.Running -> stringResource(R.string.operations_state_running)
            is OperationDisplay.State.Waiting -> stringResource(R.string.operations_state_waiting)
            is OperationDisplay.State.Completed -> stringResource(R.string.operations_state_successful)
            is OperationDisplay.State.Failed -> stringResource(R.string.operations_state_failed)
            is OperationDisplay.State.Cancelled -> stringResource(R.string.operations_state_cancelled)
        },
        timeLabel = when (operation.state) {
            is OperationDisplay.State.Completed,
            is OperationDisplay.State.Failed,
            is OperationDisplay.State.Cancelled -> stringResource(R.string.operations_details_completed_at)
            else -> stringResource(R.string.operations_details_started_at)
        },
        timeValue = when (operation.state) {
            is OperationDisplay.State.Completed -> formatRelativeTime(operation.state.completedAt)
            is OperationDisplay.State.Failed -> formatRelativeTime(operation.state.completedAt)
            is OperationDisplay.State.Cancelled -> formatRelativeTime(operation.state.completedAt)
            else -> formatRelativeTime(operation.startedAt)
        },
        durationLabel = stringResource(R.string.operations_details_duration),
        durationValue = when (operation.state) {
            is OperationDisplay.State.Queued -> stringResource(R.string.operations_details_duration_not_started)
            is OperationDisplay.State.Running,
            is OperationDisplay.State.Waiting -> formatDuration(Clock.System.now() - operation.startedAt)
            is OperationDisplay.State.Completed -> formatDuration(operation.state.completedAt - operation.startedAt)
            is OperationDisplay.State.Failed -> formatDuration(operation.state.completedAt - operation.startedAt)
            is OperationDisplay.State.Cancelled -> formatDuration(operation.state.completedAt - operation.startedAt)
        },
        destinationLabel = when (destination) {
            is OperationPathPlan.Destination.Container ->
                stringResource(R.string.operations_details_destination_folder)
            is OperationPathPlan.Destination.RequestedTarget ->
                stringResource(R.string.operations_details_destination_path)
            null -> null
        },
        destinationValue = destination?.path?.userReadablePath?.asComposable(),
        resultLabel = stringResource(R.string.operations_details_result),
        resultValue = if (reportSummary != null) reportSummary.asComposable() else null,
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val entries = when {
            maxWidth >= OverviewPairingMinWidth -> baseEntries
            else -> baseEntries.map { it.copy(pairable = false) }
        }
        // Status is always first, and referential identity is what carries the state icon -
        // identity cannot collide, whereas a label comparison breaks once two entries share a label.
        val statusEntry = entries.first()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groupInfoEntries(entries).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    row.forEach { entry ->
                        InfoBlock(
                            modifier = Modifier.weight(1f),
                            entry = entry,
                            valueLeading = if (entry === statusEntry) {
                                {
                                    val visuals = operationStateVisuals(operation.state)
                                    Icon(
                                        modifier = Modifier.size(16.dp),
                                        imageVector = visuals.imageVector,
                                        contentDescription = visuals.contentDescription,
                                        tint = visuals.tint,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                    // Keeps an odd trailing pairable entry at half width so the grid stays aligned.
                    if (row.size == 1 && row.first().pairable) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun previewReport(text: String) = object : Operation.Report {
    override val summary = text.toCaString()
    override val affectedPaths = emptyList<Operation.Report.PathChange>()
    override val subjectPath = null
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationOverviewSectionCompletedPreview() {
    val summary = "Moved 1.204 files and 87 folders, skipped 3 files, overwrote 12 files"
    Box(modifier = Modifier.width(360.dp)) {
        OperationOverviewSection(
            operation = OperationDisplay(
                id = Operation.Id(),
                startedAt = Clock.System.now() - 12.minutes,
                icon = Icons.TwoTone.Handyman,
                title = "Move".toCaString(),
                description = "Moving files".toCaString(),
                pathPlan = OperationPathPlan(
                    targets = listOf(LocalPath.build("/storage/emulated/0/Download/report.pdf")),
                    destination = OperationPathPlan.Destination.Container(
                        LocalPath.build("/storage/emulated/0/Documents/Projects/Archive/2026"),
                    ),
                ),
                state = OperationDisplay.State.Completed(
                    summary = summary.toCaString(),
                    completedAt = Clock.System.now(),
                    report = previewReport(summary),
                ),
            ),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationOverviewSectionRenamedPreview() {
    val summary = "Renamed 1 file"
    Box(modifier = Modifier.width(360.dp)) {
        OperationOverviewSection(
            operation = OperationDisplay(
                id = Operation.Id(),
                startedAt = Clock.System.now() - 1.minutes,
                icon = Icons.TwoTone.Handyman,
                title = "Rename".toCaString(),
                description = "Renaming a file".toCaString(),
                pathPlan = OperationPathPlan(
                    targets = listOf(LocalPath.build("/storage/emulated/0/Documents/report.pdf")),
                    destination = OperationPathPlan.Destination.RequestedTarget(
                        LocalPath.build(
                            "/storage/emulated/0/Documents/Projects/Archive/2026/quarterly-report-final.pdf",
                        ),
                    ),
                ),
                state = OperationDisplay.State.Completed(
                    summary = summary.toCaString(),
                    completedAt = Clock.System.now(),
                    report = previewReport(summary),
                ),
            ),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationOverviewSectionRunningPreview() {
    Box(modifier = Modifier.width(360.dp)) {
        OperationOverviewSection(
            operation = OperationDisplay(
                id = Operation.Id(),
                startedAt = Clock.System.now() - 3.minutes,
                icon = Icons.TwoTone.Handyman,
                title = "Copy".toCaString(),
                description = "Copying files".toCaString(),
                state = OperationDisplay.State.Running(),
            ),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationOverviewSectionNarrowPreview() {
    val summary = "Deleted 42 files"
    Box(modifier = Modifier.width(200.dp)) {
        OperationOverviewSection(
            operation = OperationDisplay(
                id = Operation.Id(),
                startedAt = Clock.System.now() - 2.minutes,
                icon = Icons.TwoTone.Handyman,
                title = "Delete".toCaString(),
                description = "Deleting files".toCaString(),
                state = OperationDisplay.State.Completed(
                    summary = summary.toCaString(),
                    completedAt = Clock.System.now(),
                    report = previewReport(summary),
                ),
            ),
        )
    }
}

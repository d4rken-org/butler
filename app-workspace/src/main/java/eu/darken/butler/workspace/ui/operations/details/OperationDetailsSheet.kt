package eu.darken.butler.workspace.ui.operations.details

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
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@Composable
fun OperationDetailsSheet(
    operation: OperationDisplay,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    onCancel: (() -> Unit)? = null,
    onShareError: (() -> Unit)? = null,
    onHandleIssue: (() -> Unit)? = null,
    onShowInHistory: (() -> Unit)? = null,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        modifier = modifier,
    ) {
        OperationDetailsContent(
            operation = operation,
            onCancel = onCancel,
            onShareError = onShareError,
            onHandleIssue = onHandleIssue,
            onShowInHistory = onShowInHistory,
        )
    }
}

@Composable
private fun OperationDetailsContent(
    operation: OperationDisplay,
    onCancel: (() -> Unit)? = null,
    onShareError: (() -> Unit)? = null,
    onHandleIssue: (() -> Unit)? = null,
    onShowInHistory: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp),
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

        // Progress Section (for running operations)
        if (operation.state is OperationDisplay.State.Running) {
            OperationCombinedProgressSection(
                primaryProgress = operation.state.primaryProgress,
                secondaryProgress = operation.state.secondaryProgress,
            )
        }

        // Performance Graph Section
        OperationPerformanceGraphSection(
            operation = operation
        )

        // Error Section (for failed operations)
        if (operation.state is OperationDisplay.State.Failed) {
            OperationErrorSection(
                state = operation.state,
            )
        }

        // Affected Files Section (for completed operations with affected paths)
        val affectedPaths = when (operation.state) {
            is OperationDisplay.State.Completed -> operation.state.report?.affectedPaths ?: emptyList()
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
        val hasActions = onShowInHistory != null || when (operation.state) {
            is OperationDisplay.State.Running -> onCancel != null
            is OperationDisplay.State.Failed -> onShareError != null
            is OperationDisplay.State.Waiting -> onHandleIssue != null
            else -> false
        }

        if (hasActions) {
            OperationActionsSection(
                operation = operation,
                onCancel = onCancel,
                onShareError = onShareError,
                onHandleIssue = onHandleIssue,
                onShowInHistory = onShowInHistory,
            )
        }
    }
}

@Composable
private fun OperationDetailsHeader(
    operation: OperationDisplay,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
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

            Text(
                modifier = Modifier.weight(1f),
                text = operation.title.asComposable(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        operation.description.let { description ->
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                text = description.asComposable(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


// Helper functions
private fun createMockReport(
    affectedPaths: List<Operation.Report.PathChange> = emptyList()
): Operation.Report = object : Operation.Report {
    override val summary = "Completed successfully".toCaString()
    override val affectedPaths = affectedPaths
    override val subjectPath = null
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationDetailsSheetRunningPreview() {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationDetailsSheetFailedPreview() {
    OperationDetailsSheet(
        operation = OperationDisplay(
            id = Operation.Id(),
            title = "Copy operation".toCaString(),
            description = "Failed to copy files".toCaString(),
            icon = Icons.TwoTone.ContentCopy,
            state = OperationDisplay.State.Failed(
                summary = "Insufficient space".toCaString(),
                completedAt = Clock.System.now(),
                report = createMockReport()
            ),
            startedAt = Clock.System.now() - 5.minutes,
        ),
        onDismiss = {},
        onShareError = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationDetailsSheetWaitingPreview() {
    OperationDetailsSheet(
        operation = OperationDisplay(
            id = Operation.Id(),
            title = "Copy operation".toCaString(),
            description = "Copying files to destination".toCaString(),
            icon = Icons.TwoTone.ContentCopy,
            state = OperationDisplay.State.Waiting(
                reason = "File already exists".toCaString(),
            ),
            startedAt = Clock.System.now() - 1.minutes,
        ),
        onDismiss = {},
        onHandleIssue = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationDetailsSheetCompletedWithFilesPreview() {
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
                            path = LocalPath.build("/home", "user", "documents", "file1.txt"),
                            change = Operation.Report.PathChange.Change.REMOVED
                        ),
                        Operation.Report.PathChange(
                            path = LocalPath.build("/home", "user", "documents", "file2.pdf"),
                            change = Operation.Report.PathChange.Change.REMOVED
                        ),
                        Operation.Report.PathChange(
                            path = LocalPath.build("/home", "user", "downloads", "temp"),
                            change = Operation.Report.PathChange.Change.REMOVED
                        ),
                        Operation.Report.PathChange(
                            path = LocalPath.build("/home", "user", "backup", "copy1.txt"),
                            change = Operation.Report.PathChange.Change.ADDED
                        ),
                        Operation.Report.PathChange(
                            path = LocalPath.build("/home", "user", "config.xml"),
                            change = Operation.Report.PathChange.Change.MODIFIED
                        ),
                        Operation.Report.PathChange(
                            path = LocalPath.build("/home", "user", "old", "archive.zip"),
                            change = Operation.Report.PathChange.Change.REMOVED
                        ),
                        Operation.Report.PathChange(
                            path = LocalPath.build(
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
                            path = LocalPath.build("/sdcard", "Pictures", "Screenshots", "screenshot_1.png"),
                            change = Operation.Report.PathChange.Change.REMOVED
                        ),
                    )
                )
            ),
            kind = Operation.Metadata.Kind.DELETE,
            startedAt = Clock.System.now() - 3.minutes,
        ),
        onDismiss = {},
        onShowInHistory = {},
    )
}

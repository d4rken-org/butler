package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DataUsage
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.storage.ExternalStorageStatsProvider
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.sizes.ScanProblem
import eu.darken.butler.explorer.core.sizes.AndroidDataEstimate
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import eu.darken.butler.workspace.R as WorkspaceR

/**
 * What the sizes currently shown in the listing are, and the two things that can be done with them.
 */
@Composable
fun CalculatedSizesSheet(
    modifier: Modifier = Modifier,
    state: ExplorerDialogState.CalculatedSizes,
    onRecalculate: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        modifier = modifier,
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
    ) {
        CalculatedSizesContent(
            state = state,
            onRecalculate = onRecalculate,
            onDiscard = onDiscard,
        )
    }
}

@Composable
private fun CalculatedSizesContent(
    modifier: Modifier = Modifier,
    state: ExplorerDialogState.CalculatedSizes,
    onRecalculate: () -> Unit,
    onDiscard: () -> Unit,
    initiallyShowProblems: Boolean = false,
) {
    val context = LocalContext.current
    var showProblems by rememberSaveable { mutableStateOf(initiallyShowProblems) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.TwoTone.DataUsage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = stringResource(R.string.explorer_sizes_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                text = state.root.userReadablePath.get(context),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoRow(
                label = stringResource(R.string.explorer_sizes_sheet_label_calculated),
                value = formatRelativeTime(state.scannedAt),
            )
            InfoRow(
                label = stringResource(R.string.explorer_sizes_sheet_label_folders),
                value = state.directoryCount.toString(),
            )

            if (state.errorCount > 0) {
                val unreadableLabel = stringResource(R.string.explorer_sizes_sheet_label_unreadable)
                InfoRow(
                    modifier = Modifier.clickable { showProblems = !showProblems },
                    label = unreadableLabel,
                    value = state.errorCount.toString(),
                    valueColor = MaterialTheme.colorScheme.error,
                    trailing = {
                        Icon(
                            imageVector = if (showProblems) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                            contentDescription = if (showProblems) {
                                stringResource(WorkspaceR.string.operations_details_section_collapse, unreadableLabel)
                            } else {
                                stringResource(WorkspaceR.string.operations_details_section_expand, unreadableLabel)
                            },
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                AnimatedVisibility(visible = showProblems) {
                    ProblemList(
                        problems = state.problems,
                        totalCount = state.errorCount,
                    )
                }
            }
        }

        state.estimate?.let { estimate ->
            InfoRow(
                label = estimate.path.segments.takeLast(2).joinToString("/"),
                value = stringResource(R.string.explorer_file_size_estimated, formatFileSize(estimate.displayedBytes)),
            )
            Text(
                text = stringResource(R.string.explorer_sizes_estimate_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.estimateFailure?.let { failure ->
            Text(
                text = stringResource(when (failure) {
                    AndroidDataEstimate.Failure.INCOMPLETE_COVERAGE -> R.string.explorer_sizes_estimate_coverage
                    AndroidDataEstimate.Failure.STATISTICS_UNAVAILABLE -> R.string.explorer_sizes_estimate_unavailable
                    AndroidDataEstimate.Failure.INCONSISTENT_TOTAL -> R.string.explorer_sizes_estimate_inconsistent
                }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.explorer_sizes_sheet_discard))
            }
            TextButton(onClick = onRecalculate) {
                Text(stringResource(R.string.explorer_sizes_sheet_recalculate))
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CalculatedSizesEstimatedPreview() {
    val root = LocalPath.build("/storage/emulated/0")
    CalculatedSizesContent(
        state = previewState(errorCount = 2).copy(
            root = root,
            estimate = AndroidDataEstimate(
                target = ExternalStorageStatsProvider.Target(root, Uuid.NIL, UserHandle2()),
                stats = ExternalStorageStatsProvider.Snapshot(20_000_000_000L, Clock.System.now()),
                outsideAllocatedBytes = 8_000_000_000L,
                measuredDataAllocatedBytes = 0,
                missingAllocatedBytes = 12_000_000_000L,
                displayedBytes = 12_000_000_000L,
            ),
        ),
        onRecalculate = {}, onDiscard = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CalculatedSizesEstimateUnavailablePreview() {
    CalculatedSizesContent(
        state = previewState(errorCount = 2).copy(estimateFailure = AndroidDataEstimate.Failure.INCOMPLETE_COVERAGE),
        onRecalculate = {}, onDiscard = {},
    )
}

@Composable
private fun InfoRow(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
            )
            trailing?.invoke()
        }
    }
}

@Composable
private fun ProblemList(
    modifier: Modifier = Modifier,
    problems: List<ScanProblem>,
    totalCount: Int,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items = problems) { problem ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = problem.path.userReadablePath.asComposable(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )

                problem.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (totalCount > problems.size) {
            item {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringResource(
                        R.string.explorer_sizes_sheet_problems_more,
                        totalCount - problems.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val previewProblems = listOf(
    ScanProblem(
        path = LocalPath.build("/storage/emulated/0/Download/Android/data/com.example.app"),
        message = "Permission denied",
    ),
    ScanProblem(
        path = LocalPath.build("/storage/emulated/0/Download/broken.link"),
        message = "No such file or directory",
    ),
    ScanProblem(
        path = LocalPath.build("/storage/emulated/0/Download/clip.mkv"),
        message = null,
    ),
)

private fun previewState(
    errorCount: Int = 0,
    problems: List<ScanProblem> = emptyList(),
) = ExplorerDialogState.CalculatedSizes(
    root = LocalPath.build("/storage/emulated/0/Download"),
    scannedAt = Clock.System.now() - 5.minutes,
    directoryCount = 128,
    errorCount = errorCount,
    problems = problems,
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CalculatedSizesSheetPreview() {
    PreviewWrapper {
        CalculatedSizesContent(
            state = previewState(),
            onRecalculate = {},
            onDiscard = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CalculatedSizesSheetPartialPreview() {
    PreviewWrapper {
        CalculatedSizesContent(
            state = previewState(errorCount = previewProblems.size, problems = previewProblems),
            onRecalculate = {},
            onDiscard = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CalculatedSizesSheetProblemsPreview() {
    PreviewWrapper {
        CalculatedSizesContent(
            state = previewState(errorCount = previewProblems.size, problems = previewProblems),
            onRecalculate = {},
            onDiscard = {},
            initiallyShowProblems = true,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CalculatedSizesSheetProblemsCappedPreview() {
    PreviewWrapper {
        CalculatedSizesContent(
            state = previewState(errorCount = 512, problems = previewProblems),
            onRecalculate = {},
            onDiscard = {},
            initiallyShowProblems = true,
        )
    }
}

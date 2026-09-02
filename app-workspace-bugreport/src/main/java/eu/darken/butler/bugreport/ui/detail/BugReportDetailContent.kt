package eu.darken.butler.bugreport.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.bugreport.R
import eu.darken.butler.bugreport.ui.BugReportWorkspaceViewModel
import eu.darken.butler.bugreport.ui.label
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.rememberClipboardCopy
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlin.time.Instant

/**
 * Full-screen bug-report detail: a metadata card, an optional error section (class/message/thread/
 * stack trace), and a scrollable log tail. Rendered in place of the report list within the workspace
 * pane; the toolbar's back arrow (and the host's BackHandler) return to the list.
 */
@Composable
fun BugReportDetailContent(
    modifier: Modifier = Modifier,
    design: WorkspaceDesign,
    detail: BugReportWorkspaceViewModel.Detail,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onToggleLog: (Boolean) -> Unit,
) {
    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom

    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
    )

    val report = detail.info.report
    val errorShort = report.errorClass?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
    val title = errorShort ?: report.type.label()
    val hasError = !report.errorClass.isNullOrBlank() ||
        !report.errorMessage.isNullOrBlank() ||
        !report.stackTrace.isNullOrBlank()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topBarStackState.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = topBarStackState.contentPaddingDp(),
                bottom = navBarInset + 16.dp,
                start = WorkspacePaddings.ContentHorizontal,
                end = WorkspacePaddings.ContentHorizontal,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { MetadataCard(info = detail.info) }
            if (hasError) {
                item { ErrorCard(report = report) }
            }
            logSection(
                logState = detail.logState,
                logSizeBytes = detail.info.logSizeBytes,
                isExpanded = detail.isLogExpanded,
                onToggle = { onToggleLog(!detail.isLogExpanded) },
            )
        }

        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                FloatingBar(
                    key = "toolbar",
                    visible = true,
                    scrollBehavior = BarScrollBehavior.Static,
                    animation = BarAnimation.Slide(),
                ) {
                    BugReportDetailToolbarCard(
                        title = title,
                        onBack = onBack,
                        onShare = onShare,
                        onDelete = onDelete,
                    )
                }
            },
        )
    }
}

@Composable
private fun MetadataCard(info: BugReportInfo) {
    val report = info.report
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetaField(
                    label = stringResource(R.string.bugreport_detail_field_type),
                    value = report.type.label(),
                    modifier = Modifier.weight(1f),
                )
                MetaField(
                    label = stringResource(R.string.bugreport_detail_field_time),
                    value = formatDateTime(report.createdAt, DateTimeStyle.FULL),
                    modifier = Modifier.weight(1f),
                )
            }
            MetaField(stringResource(R.string.bugreport_detail_field_app_version), report.appVersion)
            MetaField(stringResource(R.string.bugreport_detail_field_device), report.deviceFingerprint)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetaField(
                    label = stringResource(R.string.bugreport_detail_field_android),
                    value = "API ${report.apiLevel} · ${report.locale}",
                    modifier = Modifier.weight(1f),
                )
                MetaField(
                    label = stringResource(R.string.bugreport_detail_field_build),
                    value = "${report.flavor} · ${report.buildType}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(report: BugReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(
                title = stringResource(R.string.bugreport_detail_section_error),
                color = MaterialTheme.colorScheme.error,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                report.errorClass?.takeIf { it.isNotBlank() }?.let {
                    MetaField(stringResource(R.string.bugreport_detail_field_error_class), it)
                }
                report.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
                report.threadName?.takeIf { it.isNotBlank() }?.let {
                    MetaField(stringResource(R.string.bugreport_detail_field_thread), it)
                }
                report.stackTrace?.takeIf { it.isNotBlank() }?.let { trace ->
                    // A bounded inner scroll is safe inside a LazyColumn item (unlike a nested LazyColumn);
                    // SelectionContainer lets the user copy the trace.
                    SelectionContainer {
                        Text(
                            text = trace,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.logSection(
    logState: BugReportWorkspaceViewModel.LogState,
    logSizeBytes: Long,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    item {
        // Compact header: title on the left; size + line count ("3.2 kB · X lines" or
        // "… · last X of Y lines") on the right — the size lives here rather than in the metadata card.
        // The row is the toggle, so it carries the minimum interactive height itself.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { onToggle() }
                .padding(top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.bugreport_detail_section_log),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            val sizeText = formatFileSize(logSizeBytes)
            val rightText = when (logState) {
                is BugReportWorkspaceViewModel.LogState.Loaded -> if (logState.isTruncated) {
                    "$sizeText · " + stringResource(R.string.bugreport_detail_log_truncated, logState.shownLines, logState.totalLines)
                } else {
                    "$sizeText · " + stringResource(R.string.bugreport_detail_log_count, logState.totalLines)
                }
                else -> sizeText
            }
            Text(
                text = rightText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (isExpanded) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                contentDescription = stringResource(
                    if (isExpanded) {
                        eu.darken.butler.common.R.string.general_collapse_action
                    } else {
                        eu.darken.butler.common.R.string.general_expand_action
                    },
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
            )
        }
    }
    if (!isExpanded) return
    when (logState) {
        BugReportWorkspaceViewModel.LogState.Idle -> {}

        BugReportWorkspaceViewModel.LogState.Loading -> item {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        // One monospace block (not one item per line) so lines sit flush, without the list's inter-item
        // spacing between every line; SelectionContainer lets the user copy the excerpt.
        is BugReportWorkspaceViewModel.LogState.Loaded -> item {
            SelectionContainer {
                Text(
                    text = logState.lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        BugReportWorkspaceViewModel.LogState.Empty -> item {
            Text(
                text = stringResource(R.string.bugreport_detail_log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        BugReportWorkspaceViewModel.LogState.Error -> item {
            Text(
                text = stringResource(R.string.bugreport_detail_log_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MetaField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val copy = rememberClipboardCopy()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { copy(value) },
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            letterSpacing = 0.5.sp,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Normal,
        )
    }
}

private fun sampleReport(type: BugReport.Type, withError: Boolean): BugReport = BugReport(
    id = "${type.name.lowercase()}_1",
    createdAt = Instant.parse("2026-06-15T10:00:00Z"),
    type = type,
    errorClass = if (withError) "java.lang.NullPointerException" else null,
    errorMessage = if (withError) "Attempt to read from null array" else null,
    stackTrace = if (withError) {
        "java.lang.NullPointerException\n\tat eu.darken.butler.Foo.bar(Foo.kt:42)\n\tat eu.darken.butler.Baz.qux(Baz.kt:13)"
    } else {
        null
    },
    threadName = if (withError) "main" else null,
    appVersion = "v0.0.0-beta1",
    deviceFingerprint = "Google/cheetah/cheetah:14/UQ1A.240205.004/11269751:user/release-keys",
    apiLevel = "36",
    flavor = "FOSS",
    buildType = "RELEASE",
    installId = "abc",
    locale = "en-US",
)

@Preview2
@Composable
private fun BugReportDetailContentCrashPreview() {
    PreviewWrapper {
        BugReportDetailContent(
            design = WorkspaceDesign(),
            detail = BugReportWorkspaceViewModel.Detail(
                info = BugReportInfo(report = sampleReport(BugReport.Type.CRASH, withError = true), isSeen = true, logSizeBytes = 24_000L),
                logState = BugReportWorkspaceViewModel.LogState.Loaded(
                    lines = List(12) { "10:00:0$it D/Butler: log line $it" },
                    totalLines = 812,
                    shownLines = 12,
                    isTruncated = true,
                ),
                isLogExpanded = true,
            ),
            onBack = {},
            onShare = {},
            onDelete = {},
            onToggleLog = {},
        )
    }
}

@Preview2
@Composable
private fun BugReportDetailContentRecordingPreview() {
    PreviewWrapper {
        BugReportDetailContent(
            design = WorkspaceDesign(),
            detail = BugReportWorkspaceViewModel.Detail(
                info = BugReportInfo(report = sampleReport(BugReport.Type.RECORDING, withError = false), isSeen = true, logSizeBytes = 4_096L),
                logState = BugReportWorkspaceViewModel.LogState.Loaded(
                    lines = List(8) { "10:00:0$it D/Butler: recording line $it" },
                    totalLines = 8,
                    shownLines = 8,
                    isTruncated = false,
                ),
                isLogExpanded = true,
            ),
            onBack = {},
            onShare = {},
            onDelete = {},
            onToggleLog = {},
        )
    }
}

@Preview2
@Composable
private fun BugReportDetailContentLoadingPreview() {
    PreviewWrapper {
        BugReportDetailContent(
            design = WorkspaceDesign(),
            detail = BugReportWorkspaceViewModel.Detail(
                info = BugReportInfo(report = sampleReport(BugReport.Type.REPORTED, withError = true), isSeen = true, logSizeBytes = 12_000L),
                logState = BugReportWorkspaceViewModel.LogState.Loading,
                isLogExpanded = true,
            ),
            onBack = {},
            onShare = {},
            onDelete = {},
            onToggleLog = {},
        )
    }
}

@Preview2
@Composable
private fun BugReportDetailContentLogCollapsedPreview() {
    PreviewWrapper {
        BugReportDetailContent(
            design = WorkspaceDesign(),
            detail = BugReportWorkspaceViewModel.Detail(
                info = BugReportInfo(report = sampleReport(BugReport.Type.CRASH, withError = true), isSeen = true, logSizeBytes = 24_000L),
                logState = BugReportWorkspaceViewModel.LogState.Idle,
                isLogExpanded = false,
            ),
            onBack = {},
            onShare = {},
            onDelete = {},
            onToggleLog = {},
        )
    }
}

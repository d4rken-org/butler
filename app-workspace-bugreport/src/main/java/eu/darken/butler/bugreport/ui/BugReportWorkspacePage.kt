package eu.darken.butler.bugreport.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.bugreport.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.common.CutoutCard
import eu.darken.butler.workspace.ui.common.CutoutCardDefaults
import eu.darken.butler.workspace.ui.common.CutoutMode
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.launch
import kotlin.time.Instant

private val TAG = logTag("BugReport", "Workspace", "Page")

/** In-dialog log preview cap; the full log is always included in the shared zip. */
private const val MAX_LOG_PREVIEW_LINES = 300

@Composable
fun BugReportWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: BugReportWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: BugReportWorkspaceViewModel.Factory -> factory.create(id = id) },
    ),
) {
    ErrorEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<BugReportInfo?>(null) }
    var detailLog by remember { mutableStateOf<String?>(null) }
    var consentFor by remember { mutableStateOf<BugReport?>(null) }

    state?.let { s ->
        BugReportWorkspacePage(
            design = design,
            state = s,
            onReportClick = { info ->
                detail = info
                detailLog = null
                vm.markSeen(info.id)
                scope.launch { detailLog = vm.loadLog(info.id) }
            },
            onDelete = { id -> vm.delete(id); if (detail?.id == id) detail = null },
            onDeleteAll = { vm.deleteAll() },
        )
    }

    detail?.let { info ->
        BugReportDetailDialog(
            info = info,
            log = detailLog,
            onShare = {
                detail = null
                consentFor = info.report
            },
            onDelete = { vm.delete(info.id); detail = null },
            onDismiss = { detail = null },
        )
    }

    consentFor?.let { report ->
        ShareConsentDialog(
            onConfirm = {
                consentFor = null
                scope.launch {
                    try {
                        val intent = vm.buildShareIntent(report.id)
                        context.startActivity(
                            Intent.createChooser(intent, context.getString(R.string.bugreport_share_chooser_title))
                        )
                    } catch (e: Exception) {
                        log(TAG, ERROR) { "Share failed: ${e.asLog()}" }
                    }
                }
            },
            onDismiss = { consentFor = null },
        )
    }
}

@Composable
fun BugReportWorkspacePage(
    modifier: Modifier = Modifier,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: BugReportWorkspaceViewModel.State,
    onReportClick: (BugReportInfo) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onDeleteAll: () -> Unit = {},
) {
    val density = LocalDensity.current
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else {
        0.dp
    }

    val topBarStackState = rememberFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        includeSystemBarInset = design.paneEdges.touchesTop,
        estimatedContentPadding = 112.dp,
    )

    Box(modifier = modifier.fillMaxSize()) {
        if (state.reports.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topBarStackState.contentPaddingDp()),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(topBarStackState.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topBarStackState.contentPaddingDp(),
                    bottom = navBarInset + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.reports, key = { it.id }) { info ->
                    ReportCard(info = info, onClick = { onReportClick(info) })
                }
            }
        }

        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                FloatingBar(
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                    animation = BarAnimation.Slide(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    BugReportToolbarCard(
                        workspaceId = state.id,
                        design = design,
                        hasReports = state.reports.isNotEmpty(),
                        onDeleteAll = onDeleteAll,
                    )
                }
            },
        )
    }
}

@Composable
private fun BugReportToolbarCard(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    hasReports: Boolean,
    onDeleteAll: () -> Unit,
) {
    CutoutCard(
        modifier = Modifier.fillMaxWidth(),
        cutoutContent = if (design.isSingle) {
            { WorkspaceButton(currentWorkspaceId = workspaceId) }
        } else {
            null
        },
        cutoutMode = CutoutMode.FullHeight,
        // Zero vertical padding: the 48dp IconButton row defines the height, so the card matches
        // the workspace-manager button height instead of stacking padding on top of it.
        contentPadding = CutoutCardDefaults.contentPadding(horizontal = 12.dp, vertical = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Workspace.Type.BUG_REPORT.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.bugreport_workspace_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (hasReports) {
                IconButton(onClick = onDeleteAll) {
                    Icon(
                        imageVector = Icons.TwoTone.DeleteSweep,
                        contentDescription = stringResource(R.string.bugreport_delete_all_action),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportCard(
    info: BugReportInfo,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val typeLabel = when (info.report.type) {
                BugReport.Type.CRASH -> stringResource(R.string.bugreport_type_crash)
                BugReport.Type.REPORTED -> stringResource(R.string.bugreport_type_report)
            }
            val prefix = if (!info.isSeen) "• " else ""
            Text(
                text = "$prefix$typeLabel — ${info.report.errorClass.substringAfterLast('.')}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = info.report.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            Text(
                text = info.report.createdAt.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.TwoTone.BugReport,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.bugreport_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.bugreport_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BugReportDetailDialog(
    info: BugReportInfo,
    log: String?,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val report = info.report
    // Only the tail is shown — the full log travels in the shared zip. Rendered as a lazy line list
    // (not one giant Text) so a multi-hundred-KB log doesn't bloat composition or the a11y tree.
    val logLines = remember(log) {
        when (val raw = log) {
            null -> null
            else -> raw.split('\n').let { all ->
                if (all.size > MAX_LOG_PREVIEW_LINES) {
                    listOf("… last $MAX_LOG_PREVIEW_LINES of ${all.size} lines (full log in the shared report) …") +
                        all.takeLast(MAX_LOG_PREVIEW_LINES)
                } else {
                    all
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(report.errorClass.substringAfterLast('.')) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(report.errorMessage, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${report.appVersion}\n${report.deviceFingerprint}\nAPI ${report.apiLevel} · ${report.locale}\n${report.createdAt}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    if (logLines == null) {
                        item { Text("…", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        items(logLines) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) { Text(stringResource(R.string.bugreport_share_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.bugreport_delete_action)) }
        },
    )
}

@Composable
private fun ShareConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.TwoTone.ReportProblem, contentDescription = null) },
        title = { Text(stringResource(R.string.bugreport_share_consent_title)) },
        text = { Text(stringResource(R.string.bugreport_share_consent_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.bugreport_share_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bugreport_cancel_action)) }
        },
    )
}

@Preview2
@Composable
private fun BugReportWorkspacePageEmptyPreview() {
    PreviewWrapper {
        BugReportWorkspacePage(
            state = BugReportWorkspaceViewModel.State(id = Workspace.Id(), reports = emptyList()),
        )
    }
}

@Preview2
@Composable
private fun BugReportWorkspacePagePreview() {
    PreviewWrapper {
        BugReportWorkspacePage(
            state = BugReportWorkspaceViewModel.State(
                id = Workspace.Id(),
                reports = listOf(
                    BugReportInfo(
                        report = BugReport(
                            id = "crash_1",
                            createdAt = Instant.parse("2026-06-15T10:00:00Z"),
                            type = BugReport.Type.CRASH,
                            errorClass = "java.lang.NullPointerException",
                            errorMessage = "Attempt to read from null array",
                            stackTrace = "",
                            threadName = "main",
                            appVersion = "v0.0.0-beta1",
                            deviceFingerprint = "Pixel/foo",
                            apiLevel = "36",
                            flavor = "FOSS",
                            buildType = "RELEASE",
                            installId = "abc",
                            locale = "en-US",
                        ),
                        isSeen = false,
                    ),
                ),
            ),
        )
    }
}

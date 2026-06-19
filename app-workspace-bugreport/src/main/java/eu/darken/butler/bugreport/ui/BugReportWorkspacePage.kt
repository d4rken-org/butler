package eu.darken.butler.bugreport.ui

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.FiberManualRecord
import androidx.compose.material.icons.twotone.ReportProblem
import androidx.compose.material.icons.twotone.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
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
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.delay
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
    var showShortRecordingWarning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                BugReportWorkspaceViewModel.Event.ShowShortRecordingWarning -> showShortRecordingWarning = true
            }
        }
    }

    state?.let { s ->
        BugReportWorkspacePage(
            design = design,
            state = s,
            onReportClick = { info ->
                // Ongoing recordings have no finished detail to show yet.
                if (info.isOngoingRecording) return@BugReportWorkspacePage
                detail = info
                detailLog = null
                vm.markSeen(info.id)
                scope.launch { detailLog = vm.loadLog(info.id) }
            },
            onDelete = { id -> vm.delete(id); if (detail?.id == id) detail = null },
            onDeleteAll = { vm.deleteAll() },
            onStartRecording = { vm.startRecording() },
            onStopRecording = { vm.stopRecording() },
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

    if (showShortRecordingWarning) {
        ShortRecordingWarningDialog(
            onKeepRecording = { showShortRecordingWarning = false },
            onStopAnyway = {
                showShortRecordingWarning = false
                vm.forceStopRecording()
            },
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
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
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

    // The ongoing recording is represented by the toolbar's recording row, never as a list card —
    // there can only ever be one, and it becomes a normal report once stopped.
    val listReports = state.reports.filter { !it.isOngoingRecording }

    Box(modifier = modifier.fillMaxSize()) {
        if (listReports.isEmpty()) {
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
                items(listReports, key = { it.id }) { info ->
                    ReportCard(info = info, onClick = { onReportClick(info) })
                }
            }
        }

        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                // A single toolbar card. When recording it grows an extra inner row (the recording
                // controls) — like the Searcher toolbar grows to hold its filters — and shrinks back
                // when stopped or scroll-collapsed.
                FloatingBar(
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll(),
                    animation = BarAnimation.Slide(),
                    estimatedHeight = 64.dp,
                    revealOn = state.isRecording,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    BugReportToolbarCard(
                        workspaceId = state.id,
                        design = design,
                        hasReports = listReports.isNotEmpty(),
                        isRecording = state.isRecording,
                        recordingStartedAt = state.recordingStartedAt,
                        recordingLogSize = state.recordingLogSize,
                        collapsedFraction = collapsedFraction,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
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
    isRecording: Boolean,
    recordingStartedAt: Long,
    recordingLogSize: Long,
    collapsedFraction: Float,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val context = LocalContext.current
    val isCollapsed = collapsedFraction > 0.5f
    // Only the recording-expanded state needs vertical breathing room (the nested control panel).
    // The header-only states (idle-expanded, collapsed) drop vertical padding so the 48dp action
    // buttons — and the cutout's workspace button — define the card height, instead of inflating it.
    val isRecordingExpanded = isRecording && !isCollapsed
    val horizontalPadding by animateDpAsState(
        targetValue = if (isCollapsed) CutoutCardDefaults.ContentPaddingCollapsed else CutoutCardDefaults.ContentPaddingExpanded,
        label = "bugReportCardPaddingH",
    )
    val verticalPadding by animateDpAsState(
        targetValue = if (isRecordingExpanded) CutoutCardDefaults.ContentPaddingExpanded else 0.dp,
        label = "bugReportCardPaddingV",
    )

    // One ticker for the whole card — feeds both the collapsed summary and the expanded control row.
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isRecording, recordingStartedAt) {
        if (!isRecording) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)
            delay(1000)
        }
    }

    CutoutCard(
        modifier = Modifier.fillMaxWidth(),
        cutoutContent = if (design.isSingle) {
            {
                WorkspaceButton(
                    currentWorkspaceId = workspaceId,
                    buttonSize = if (isCollapsed) WorkspaceButtonDefaults.sizeCompact else WorkspaceButtonDefaults.sizeDefault,
                )
            }
        } else {
            null
        },
        cutoutMode = if (isCollapsed) CutoutMode.FullHeight else CutoutMode.Auto,
        gapDistance = if (isCollapsed) CutoutCardDefaults.GapDistanceCollapsed else CutoutCardDefaults.GapDistanceExpanded,
        contentPadding = CutoutCardDefaults.contentPadding(horizontal = horizontalPadding, vertical = verticalPadding),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row — either the normal title + actions, or (while collapsed and recording) a
            // compact recording summary that keeps Stop reachable without expanding the card.
            // The min height keeps the bar tappable/visible even in states with no action buttons or
            // cutout (e.g. idle-collapsed in a multi-pane layout), where vertical padding is 0.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isRecording && isCollapsed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    RecordingDot()
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "${formatElapsed(elapsedMs)} · ${Formatter.formatShortFileSize(context, recordingLogSize)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onStopRecording) {
                        Icon(
                            imageVector = Icons.TwoTone.Stop,
                            contentDescription = stringResource(R.string.bugreport_stop_action),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
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
                    if (!isCollapsed && !isRecording) {
                        IconButton(onClick = onStartRecording) {
                            Icon(
                                imageVector = Icons.TwoTone.FiberManualRecord,
                                contentDescription = stringResource(R.string.bugreport_record_action),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (!isCollapsed && hasReports) {
                        IconButton(onClick = onDeleteAll) {
                            Icon(
                                imageVector = Icons.TwoTone.DeleteSweep,
                                contentDescription = stringResource(R.string.bugreport_delete_all_action),
                            )
                        }
                    }
                }
            }

            // The card grows to hold the recording controls while recording — the same way the
            // Searcher toolbar grows to hold its filter rows.
            AnimatedVisibility(
                visible = isRecording && !isCollapsed,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                RecordingControlRow(
                    elapsedMs = elapsedMs,
                    logSize = recordingLogSize,
                    onStop = onStopRecording,
                )
            }
        }
    }
}

@Composable
private fun ReportCard(
    info: BugReportInfo,
    onClick: () -> Unit,
) {
    val report = info.report
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val typeLabel = report.type.label()
            val prefix = if (!info.isSeen) "• " else ""
            val detail = report.errorClass?.substringAfterLast('.')
            Text(
                text = prefix + typeLabel + if (!detail.isNullOrBlank()) " — $detail" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            report.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
            }
            Text(
                text = report.createdAt.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The recording controls that live INSIDE the toolbar card while a recording is active — a nested
 * error-tinted panel (the analog of the Searcher toolbar's filter section): pulsing REC dot, the
 * "Recording…" label, live elapsed time + log size, and a Stop button.
 */
@Composable
private fun RecordingControlRow(
    elapsedMs: Long,
    logSize: Long,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingDot()
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.bugreport_recording_ongoing_label),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatElapsed(elapsedMs),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = " · ${Formatter.formatShortFileSize(context, logSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            FilledTonalButton(
                onClick = onStop,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(Icons.TwoTone.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.bugreport_stop_short_action))
            }
        }
    }
}

/** A pulsing red dot signalling an active recording. */
@Composable
private fun RecordingDot(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "rec")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "dot",
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error.copy(alpha = dotAlpha)),
    )
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
    val title = report.errorClass?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: report.type.label()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                report.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
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

@Composable
private fun ShortRecordingWarningDialog(
    onKeepRecording: () -> Unit,
    onStopAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepRecording,
        title = { Text(stringResource(R.string.bugreport_recording_short_title)) },
        text = { Text(stringResource(R.string.bugreport_recording_short_message)) },
        confirmButton = {
            TextButton(onClick = onStopAnyway) { Text(stringResource(R.string.bugreport_stop_action)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepRecording) { Text(stringResource(R.string.bugreport_recording_short_keep_action)) }
        },
    )
}

@Composable
private fun BugReport.Type.label(): String = when (this) {
    BugReport.Type.CRASH -> stringResource(R.string.bugreport_type_crash)
    BugReport.Type.REPORTED -> stringResource(R.string.bugreport_type_report)
    BugReport.Type.RECORDING -> stringResource(R.string.bugreport_type_recording)
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
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
                isRecording = true,
                recordingLogSize = 24_000L,
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

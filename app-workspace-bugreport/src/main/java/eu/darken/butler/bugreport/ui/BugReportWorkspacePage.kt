package eu.darken.butler.bugreport.ui

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import eu.darken.butler.bugreport.R
import eu.darken.butler.bugreport.ui.detail.BugReportDetailContent
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.common.CutoutCard
import eu.darken.butler.workspace.ui.common.CutoutCardDefaults
import eu.darken.butler.workspace.ui.common.CutoutMode
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import kotlinx.coroutines.delay
import kotlin.time.Instant
import eu.darken.butler.common.R as CommonR

@Composable
fun BugReportWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: BugReportWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: BugReportWorkspaceViewModel.Factory -> factory.create(id = id) },
    ),
) {
    val state by vm.state.collectAsState(initial = null)
    val overlayState by vm.overlayState.collectAsState()

    state?.let { s ->
        // While the detail view is open, back returns to the list — but let an open dialog consume
        // back first so it isn't dismissed together with the detail.
        WorkspaceBackHandler(enabled = s.detail != null && overlayState.activeDialog == null) { vm.closeReport() }

        BugReportWorkspacePage(
            design = design,
            state = s,
            onReportClick = { info -> vm.openReport(info.id) },
            onBack = { vm.closeReport() },
            onShareReport = { report -> vm.requestShareConsent(report.id) },
            onDeleteReport = { id -> vm.delete(id) },
            onDeleteAll = { vm.requestDeleteAllConfirmation() },
            onStartRecording = { vm.startRecording() },
            onStopRecording = { vm.stopRecording() },
        )
    }
}

@Composable
fun BugReportWorkspacePage(
    modifier: Modifier = Modifier,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: BugReportWorkspaceViewModel.State,
    onReportClick: (BugReportInfo) -> Unit = {},
    onBack: () -> Unit = {},
    onShareReport: (BugReport) -> Unit = {},
    onDeleteReport: (String) -> Unit = {},
    onDeleteAll: () -> Unit = {},
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
) {
    val detail = state.detail
    if (detail != null) {
        BugReportDetailContent(
            modifier = modifier,
            design = design,
            detail = detail,
            onBack = onBack,
            onShare = { onShareReport(detail.info.report) },
            onDelete = { onDeleteReport(detail.info.id) },
        )
        return
    }

    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
        estimatedContentPadding = 112.dp,
    )
    val bottomBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.BOTTOM,
        workspaceId = state.id,
        design = design,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        estimatedContentPadding = 80.dp,
    )
    val listContentPadding = rememberFloatingBarContentPadding(
        topBarStackState,
        bottomBarStackState,
        start = WorkspacePaddings.ContentHorizontal,
        end = WorkspacePaddings.ContentHorizontal,
    )

    // The ongoing recording is represented by the toolbar's recording row, never as a list card —
    // there can only ever be one, and it becomes a normal report once stopped.
    val listReports = state.reports.filter { !it.isOngoingRecording }

    Box(modifier = modifier.fillMaxSize()) {
        if (listReports.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = topBarStackState.contentPaddingDp(),
                        bottom = bottomBarStackState.contentPaddingDp(),
                    ),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(topBarStackState.nestedScrollConnection),
                contentPadding = listContentPadding,
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
                    key = "toolbar",
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll,
                    animation = BarAnimation.Slide(),
                    estimatedHeight = 64.dp,
                    revealOn = state.isRecording,
                ) {
                    // Derive the boolean here so the card only recomposes at the collapse threshold,
                    // not on every scroll frame (collapsedFraction changes continuously while scrolling
                    // and the card's CutoutCard re-measures via SubcomposeLayout on each recomposition).
                    BugReportToolbarCard(
                        workspaceId = state.id,
                        design = design,
                        hasReports = listReports.isNotEmpty(),
                        isRecording = state.isRecording,
                        recordingStartedAt = state.recordingStartedAt,
                        recordingLogSize = state.recordingLogSize,
                        isCollapsed = collapsedFraction > 0.5f,
                        onStopRecording = onStopRecording,
                        onDeleteAll = onDeleteAll,
                    )
                }
            },
        )

        FloatingBarStack(
            state = bottomBarStackState,
            position = BarPosition.BOTTOM,
            modifier = Modifier.align(Alignment.BottomCenter),
            bars = {
                // Static: the toolbar's recording controls disappear on scroll-collapse, so this is
                // the only in-pane stop control that is guaranteed to be reachable.
                FloatingBar(
                    key = "record",
                    visible = true,
                    scrollBehavior = BarScrollBehavior.Static,
                    estimatedHeight = 56.dp,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        RecordButton(
                            isRecording = state.isRecording,
                            onStartRecording = onStartRecording,
                            onStopRecording = onStopRecording,
                        )
                    }
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
    isCollapsed: Boolean,
    onStopRecording: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val context = LocalContext.current
    // Only the recording-expanded state needs vertical breathing room (the nested control panel).
    // The header-only states (idle-expanded, collapsed) drop vertical padding so the action buttons —
    // and the cutout's workspace button — define the card height, instead of inflating it.
    val isRecordingExpanded = isRecording && !isCollapsed
    // Collapsed, the card should be exactly as tall as the compact workspace ("manager") button so it
    // lines up with the other workspaces' toolbars; expanded, it matches the default-size button.
    val headerMinHeight = if (isCollapsed) WorkspaceButtonDefaults.sizeCompact else WorkspaceButtonDefaults.sizeDefault
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
            // compact recording readout: dot, elapsed time and log size.
            // The min height keeps the bar tappable/visible even in states with no action buttons or
            // cutout (e.g. idle-collapsed in a multi-pane layout), where vertical padding is 0.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = headerMinHeight)
                    // Match the collapsed inset used by the other workspaces' toolbars (e.g. Searcher).
                    .padding(start = if (isCollapsed) 8.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isRecording && isCollapsed) {
                    RecordingDot()
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "${formatElapsed(elapsedMs)} · ${Formatter.formatShortFileSize(context, recordingLogSize)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
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

/**
 * Starts/stops a recording from the bottom bar. The error tint only appears while recording, so an
 * otherwise empty screen isn't dominated by a red pill.
 *
 * The content description uses the long-form strings ("Record debug log" / "Stop recording"): the
 * visible label is a bare "Record"/"Stop", and the toolbar's recording row renders a "Stop" of its
 * own, so visible text alone identifies neither for a screen reader nor for a test.
 */
@Composable
private fun RecordButton(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val description = stringResource(
        if (isRecording) R.string.bugreport_stop_action else R.string.bugreport_record_action,
    )
    val defaultContainerColor = FloatingActionButtonDefaults.containerColor
    val containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer else defaultContainerColor
    ExtendedFloatingActionButton(
        onClick = if (isRecording) onStopRecording else onStartRecording,
        modifier = modifier.semantics { contentDescription = description },
        containerColor = containerColor,
        contentColor = if (isRecording) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            contentColorFor(defaultContainerColor)
        },
        icon = {
            if (isRecording) {
                Icon(imageVector = Icons.TwoTone.Stop, contentDescription = null)
            } else {
                Icon(
                    imageVector = Icons.TwoTone.FiberManualRecord,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        text = {
            Text(
                stringResource(
                    if (isRecording) R.string.bugreport_stop_short_action else R.string.bugreport_record_short_action,
                ),
            )
        },
    )
}

@Preview2
@Composable
private fun RecordButtonIdlePreview() {
    PreviewWrapper {
        RecordButton(isRecording = false, onStartRecording = {}, onStopRecording = {})
    }
}

@Preview2
@Composable
private fun RecordButtonRecordingPreview() {
    PreviewWrapper {
        RecordButton(isRecording = true, onStartRecording = {}, onStopRecording = {})
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
                text = formatDateTime(report.createdAt, DateTimeStyle.COMPACT),
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
            color = MaterialTheme.colorScheme.onSurface,
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
internal fun ShareConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onPrivacyPolicy: () -> Unit,
) {
    PaneBoundAlertDialog(
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
        neutralButton = {
            TextButton(onClick = onPrivacyPolicy) {
                Text(stringResource(CommonR.string.general_privacy_policy_action))
            }
        },
    )
}

@Composable
internal fun ShortRecordingWarningDialog(
    onKeepRecording: () -> Unit,
    onStopAnyway: () -> Unit,
) {
    PaneBoundAlertDialog(
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
internal fun DeleteAllConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.TwoTone.DeleteSweep,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.bugreport_delete_all_confirm_title)) },
        text = { Text(stringResource(R.string.bugreport_delete_all_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.bugreport_delete_all_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.bugreport_cancel_action)) }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeleteAllConfirmationDialogPreview() {
    DeleteAllConfirmationDialog(onConfirm = {}, onDismiss = {})
}

@Composable
internal fun BugReport.Type.label(): String = when (this) {
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

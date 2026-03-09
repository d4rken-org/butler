package eu.darken.butler.main.ui.settings.support

import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Book
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Email
import androidx.compose.material.icons.twotone.FiberManualRecord
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.darken.butler.R
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.icons.Discord
import eu.darken.butler.common.debug.recorder.core.DebugSession
import eu.darken.butler.common.debug.recorder.ui.ShortRecordingDialog
import eu.darken.butler.common.debug.recorder.ui.result.RecorderActivity
import eu.darken.butler.common.debug.recorder.ui.result.RecorderConsentDialog
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsPreferenceItem

private sealed interface SupportDialog {
    data object Consent : SupportDialog
    data object ShortRecordingWarning : SupportDialog
    data class DeleteSession(val sessionId: String) : SupportDialog
    data object ClearLogs : SupportDialog
}

@Composable
fun SupportScreenHost(vm: SupportScreenViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    LifecycleResumeEffect(Unit) {
        vm.refreshSessions()
        onPauseOrDispose {}
    }

    val context = LocalContext.current
    val state by vm.state.collectAsState(initial = null)
    var dialog by remember { mutableStateOf<SupportDialog?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                SupportScreenViewModel.Event.ShowConsentDialog -> {
                    dialog = SupportDialog.Consent
                }

                SupportScreenViewModel.Event.ShowShortRecordingWarning -> {
                    dialog = SupportDialog.ShortRecordingWarning
                }

                is SupportScreenViewModel.Event.OpenRecorderActivity -> {
                    val intent = RecorderActivity.getLaunchIntent(context, event.sessionId, event.legacyPath).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    when (val d = dialog) {
        is SupportDialog.Consent -> {
            RecorderConsentDialog(
                onDismissRequest = { dialog = null },
                onConfirm = {
                    dialog = null
                    vm.startDebugLog()
                },
                onOpenPrivacyPolicy = {
                    vm.openUrl(ButlerLinks.PRIVACY_POLICY)
                },
            )
        }

        is SupportDialog.ShortRecordingWarning -> {
            ShortRecordingDialog(
                onKeepRecording = { dialog = null },
                onStopAnyway = {
                    dialog = null
                    vm.forceStopDebugLog()
                },
            )
        }

        is SupportDialog.DeleteSession -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.support_debuglog_session_delete_title)) },
                text = { Text(stringResource(R.string.support_debuglog_session_delete_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        vm.deleteSession(d.sessionId)
                    }) {
                        Text(stringResource(R.string.support_debuglog_delete_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) {
                        Text(stringResource(R.string.general_cancel_action))
                    }
                },
            )
        }

        is SupportDialog.ClearLogs -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.support_debuglog_delete_confirm_title)) },
                text = { Text(stringResource(R.string.support_debuglog_clear_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        vm.clearDebugLogs()
                    }) {
                        Text(stringResource(R.string.support_debuglog_delete_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) {
                        Text(stringResource(R.string.general_cancel_action))
                    }
                },
            )
        }

        null -> {}
    }

    state?.let {
        SupportScreen(
            state = it,
            onNavigateUp = { vm.navUp() },
            onOpenUrl = { url -> vm.openUrl(url) },
            onContactSupport = { vm.contactSupport() },
            onDebugLogToggle = { vm.onDebugLogToggle() },
            onOpenSession = { id -> vm.openSession(id) },
            onDeleteSession = { id -> dialog = SupportDialog.DeleteSession(id) },
            onStopRecording = { vm.onDebugLogToggle() },
            onClearLogs = { dialog = SupportDialog.ClearLogs },
        )
    }
}

@Composable
fun SupportScreen(
    state: SupportScreenViewModel.State,
    onNavigateUp: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onContactSupport: () -> Unit,
    onDebugLogToggle: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStopRecording: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showSessionsSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_support_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action),
                        )
                    }
                },
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Top
        ) {
            item { SettingsCategoryHeader(stringResource(R.string.settings_support_category_help_label)) }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Book,
                    title = stringResource(R.string.settings_support_wiki_label),
                    subtitle = stringResource(R.string.settings_support_wiki_description),
                    onClick = { onOpenUrl(ButlerLinks.WIKI) }
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.BugReport,
                    title = stringResource(R.string.settings_support_issue_tracker_label),
                    subtitle = stringResource(R.string.settings_support_issue_tracker_description),
                    onClick = { onOpenUrl(ButlerLinks.ISSUES) }
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.Filled.Discord,
                    title = stringResource(R.string.settings_support_discord_label),
                    subtitle = stringResource(R.string.settings_support_discord_description),
                    onClick = { onOpenUrl("https://discord.gg/ktMbDBAp4K") }
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Email,
                    title = stringResource(R.string.support_contact_label),
                    subtitle = stringResource(R.string.support_contact_description),
                    onClick = onContactSupport,
                )
                SettingsDivider()
            }

            item { SettingsCategoryHeader(stringResource(R.string.settings_category_other_label)) }

            item {
                SettingsPreferenceItem(
                    icon = if (state.isRecording) Icons.TwoTone.Cancel else Icons.TwoTone.Description,
                    title = if (state.isRecording) {
                        stringResource(R.string.settings_support_debuglog_stop_action)
                    } else {
                        stringResource(R.string.settings_support_debuglog_record_action)
                    },
                    subtitle = if (state.isRecording) {
                        state.currentLogPath?.path
                    } else {
                        stringResource(R.string.settings_support_debuglog_desc)
                    },
                    onClick = onDebugLogToggle,
                )
                SettingsDivider()
            }

            if (state.sessions.isNotEmpty()) {
                item {
                    val nonRecordingSessions = state.logSessionCount
                    val logSizeFormatted = Formatter.formatShortFileSize(context, state.logFolderSize)
                    SettingsPreferenceItem(
                        icon = Icons.TwoTone.FolderOpen,
                        title = stringResource(R.string.support_debuglog_sessions_label),
                        subtitle = if (nonRecordingSessions > 0) {
                            stringResource(
                                R.string.support_debuglog_storage_description,
                                nonRecordingSessions,
                                logSizeFormatted,
                            )
                        } else {
                            stringResource(R.string.support_debuglog_sessions_desc)
                        },
                        onClick = { showSessionsSheet = true },
                    )
                }
            }
        }
    }

    if (showSessionsSheet) {
        DebugSessionsBottomSheet(
            sessions = state.sessions,
            onDismiss = { showSessionsSheet = false },
            onOpenSession = onOpenSession,
            onDeleteSession = onDeleteSession,
            onStopRecording = onStopRecording,
            onClearAll = {
                showSessionsSheet = false
                onClearLogs()
            },
        )
    }
}

@Composable
private fun DebugSessionsBottomSheet(
    sessions: List<DebugSession>,
    onDismiss: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStopRecording: () -> Unit,
    onClearAll: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.support_debuglog_sessions_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (sessions.any { it !is DebugSession.Recording }) {
                    IconButton(onClick = onClearAll) {
                        Icon(
                            imageVector = Icons.TwoTone.Delete,
                            contentDescription = stringResource(R.string.support_debuglog_clear_action),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.support_debuglog_sessions_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            } else {
                sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        context = context,
                        onOpen = { onOpenSession(session.id) },
                        onDelete = { onDeleteSession(session.id) },
                        onStop = onStopRecording,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: DebugSession,
    context: android.content.Context,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (session is DebugSession.Ready) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (session) {
            is DebugSession.Recording -> Icon(
                imageVector = Icons.TwoTone.FiberManualRecord,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.error,
            )

            is DebugSession.Compressing -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )

            is DebugSession.Ready -> Icon(
                imageVector = Icons.TwoTone.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            is DebugSession.Failed -> Icon(
                imageVector = Icons.TwoTone.Warning,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val agoText = DateUtils.getRelativeTimeSpanString(
                session.createdAt.toEpochMilliseconds(),
                System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            )
            Text(
                text = when (session) {
                    is DebugSession.Recording -> stringResource(R.string.support_debuglog_session_recording)
                    is DebugSession.Compressing -> stringResource(R.string.support_debuglog_session_compressing)
                    is DebugSession.Ready -> "${Formatter.formatShortFileSize(context, session.diskSize)} \u00B7 $agoText"
                    is DebugSession.Failed -> when (session.reason) {
                        DebugSession.Failed.Reason.EMPTY_LOG -> stringResource(R.string.support_debuglog_session_failed_empty_log)
                        DebugSession.Failed.Reason.MISSING_LOG -> stringResource(R.string.support_debuglog_session_failed_missing_log)
                        DebugSession.Failed.Reason.CORRUPT_ZIP -> stringResource(R.string.support_debuglog_session_failed_corrupt_zip)
                        DebugSession.Failed.Reason.ZIP_FAILED -> stringResource(R.string.support_debuglog_session_failed_zip_failed)
                    } + " \u00B7 $agoText"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (session) {
            is DebugSession.Recording -> IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.TwoTone.Cancel,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            is DebugSession.Compressing -> {}

            is DebugSession.Ready -> IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.TwoTone.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is DebugSession.Failed -> IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.TwoTone.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview2
@Composable
private fun DebugSessionsBottomSheetPreview() {
    PreviewWrapper {
        DebugSessionsBottomSheet(
            sessions = listOf(
                DebugSession.Recording(
                    id = "ext:session_recording",
                    displayName = "eu.darken.butler_100_2024-03-07_11-20-00-000",
                    createdAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    diskSize = 12345L,
                    path = java.io.File("/data/debug/logs/session_recording"),
                    startedAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis() - 30_000),
                ),
                DebugSession.Ready(
                    id = "ext:session_ready",
                    displayName = "eu.darken.butler_100_2024-03-06_10-00-00-000",
                    createdAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis() - 86_400_000),
                    diskSize = 54321L,
                    logDir = java.io.File("/data/debug/logs/session_ready"),
                    zipFile = java.io.File("/data/debug/logs/session_ready.zip"),
                    compressedSize = 12000L,
                ),
                DebugSession.Failed(
                    id = "ext:session_failed",
                    displayName = "eu.darken.butler_100_2024-03-05_09-00-00-000",
                    createdAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis() - 172_800_000),
                    diskSize = 0L,
                    path = java.io.File("/data/debug/logs/session_failed"),
                    reason = DebugSession.Failed.Reason.EMPTY_LOG,
                ),
            ),
            onDismiss = {},
            onOpenSession = {},
            onDeleteSession = {},
            onStopRecording = {},
            onClearAll = {},
        )
    }
}

@Preview2
@Composable
private fun SupportScreenPreview() {
    PreviewWrapper {
        SupportScreen(
            state = SupportScreenViewModel.State(),
            onNavigateUp = {},
            onOpenUrl = {},
            onContactSupport = {},
            onDebugLogToggle = {},
            onOpenSession = {},
            onDeleteSession = {},
            onStopRecording = {},
            onClearLogs = {},
        )
    }
}

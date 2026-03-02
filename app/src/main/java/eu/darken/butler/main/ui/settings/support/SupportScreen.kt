package eu.darken.butler.main.ui.settings.support

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Book
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Email
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.darken.butler.R
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.icons.Discord
import eu.darken.butler.common.debug.recorder.ui.result.RecorderConsentDialog
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsPreferenceItem
import androidx.compose.runtime.collectAsState
import java.io.File

@Composable
fun SupportScreenHost(vm: SupportScreenViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    LifecycleResumeEffect(Unit) {
        vm.refreshDebugLogFolderStats()
        onPauseOrDispose {}
    }

    val state by vm.state.collectAsState(initial = null)

    state?.let { vmState ->
        SupportScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onDebugLog = { vm.debugLog() },
            onOpenUrl = { url -> vm.openUrl(url) },
            onContactSupport = { vm.contactSupport() },
            onDeleteAllDebugLogs = { vm.deleteAllDebugLogs() },
        )
    }
}

@Composable
fun SupportScreen(
    state: SupportScreenViewModel.State,
    onNavigateUp: () -> Unit,
    onDebugLog: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onContactSupport: () -> Unit,
    onDeleteAllDebugLogs: () -> Unit,
) {
    var showConsentDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    if (showConsentDialog) {
        RecorderConsentDialog(
            onDismissRequest = { showConsentDialog = false },
            onConfirm = {
                showConsentDialog = false
                onDebugLog()
            },
            onOpenPrivacyPolicy = {
                onOpenUrl(ButlerLinks.PRIVACY_POLICY)
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.support_debuglog_delete_confirm_title)) },
            text = { Text(stringResource(R.string.support_debuglog_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    onDeleteAllDebugLogs()
                }) {
                    Text(stringResource(R.string.support_debuglog_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.general_cancel_action))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_support_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                stringResource(
                                    eu.darken
                                        .butler
                                        .common
                                        .R
                                        .string
                                        .general_back_action
                                )
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Top
        ) {
            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Email,
                    title = stringResource(R.string.support_contact_label),
                    subtitle = stringResource(R.string.support_contact_description),
                    onClick = onContactSupport,
                )
                SettingsDivider()
            }

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
                    subtitle =
                        stringResource(R.string.settings_support_issue_tracker_description),
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

            item { SettingsCategoryHeader(stringResource(R.string.settings_category_other_label)) }

            item {
                SettingsPreferenceItem(
                    icon = if (state.isRecording) Icons.TwoTone.Notifications else Icons.TwoTone.Description,
                    title = stringResource(R.string.settings_support_debuglog_label),
                    subtitle = if (state.isRecording) {
                        stringResource(R.string.settings_support_debuglog_recording_desc) +
                            (state.logPath?.let {
                                "\n" + stringResource(
                                    R.string.settings_support_debuglog_path,
                                    it.path
                                )
                            } ?: "")
                    } else {
                        stringResource(R.string.settings_support_debuglog_desc)
                    },
                    onClick = {
                        if (state.isRecording) {
                            onDebugLog()
                        } else {
                            showConsentDialog = true
                        }
                    }
                )
                SettingsDivider()
            }

            item {
                val context = LocalContext.current
                val stats = state.debugLogFolderStats
                val subtitle = if (stats != null && stats.fileCount > 0) {
                    stringResource(
                        R.string.support_debuglog_storage_description,
                        stats.fileCount,
                        Formatter.formatShortFileSize(context, stats.totalSizeBytes),
                    )
                } else {
                    stringResource(R.string.support_debuglog_storage_empty)
                }
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.FolderOpen,
                    title = stringResource(R.string.support_debuglog_storage_label),
                    subtitle = subtitle,
                    onClick = {},
                )
                SettingsDivider()
            }

            item {
                val hasLogs = (state.debugLogFolderStats?.fileCount ?: 0) > 0
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Delete,
                    title = stringResource(R.string.support_debuglog_delete_action),
                    subtitle = stringResource(R.string.support_debuglog_delete_description),
                    enabled = hasLogs,
                    onClick = { showDeleteConfirmDialog = true },
                )
            }
        }
    }
}

@Preview2
@Composable
private fun SupportScreenRecordingPreview() {
    PreviewWrapper {
        SupportScreen(
            state = SupportScreenViewModel.State(
                isRecording = true,
                logPath = File("/tmp/debug.log"),
                debugLogFolderStats = SupportScreenViewModel.DebugLogFolderStats(
                    fileCount = 3,
                    totalSizeBytes = 1_500_000L,
                ),
            ),
            onNavigateUp = {},
            onDebugLog = {},
            onOpenUrl = {},
            onContactSupport = {},
            onDeleteAllDebugLogs = {},
        )
    }
}

@Preview2
@Composable
private fun SupportScreenNotRecordingPreview() {
    PreviewWrapper {
        SupportScreen(
            state = SupportScreenViewModel.State(
                isRecording = false,
                logPath = null,
                debugLogFolderStats = SupportScreenViewModel.DebugLogFolderStats(
                    fileCount = 0,
                    totalSizeBytes = 0L,
                ),
            ),
            onNavigateUp = {},
            onDebugLog = {},
            onOpenUrl = {},
            onContactSupport = {},
            onDeleteAllDebugLogs = {},
        )
    }
}

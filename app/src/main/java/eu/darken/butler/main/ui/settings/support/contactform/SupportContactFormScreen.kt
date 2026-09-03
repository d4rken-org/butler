package eu.darken.butler.main.ui.settings.support.contactform

import android.content.ActivityNotFoundException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.twotone.ContactSupport
import androidx.compose.material.icons.automirrored.twotone.Send
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.debug.recorder.ui.ShortRecordingDialog
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.common.navigation.NavigationEventHandler
import kotlin.time.Instant

private sealed interface ContactFormDialog {
    data object SentConfirm : ContactFormDialog
    data object ShortRecordingWarning : ContactFormDialog
    data class DeleteReport(val reportId: String) : ContactFormDialog
}

@Composable
fun SupportContactFormScreenHost(
    vm: SupportContactFormViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var hasSentEmail by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<ContactFormDialog?>(null) }

    LifecycleResumeEffect(hasSentEmail) {
        if (hasSentEmail) {
            dialog = ContactFormDialog.SentConfirm
            hasSentEmail = false
        }
        onPauseOrDispose {}
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is SupportContactFormViewModel.Event.OpenEmail -> {
                    try {
                        hasSentEmail = true
                        context.startActivity(event.intent)
                    } catch (_: ActivityNotFoundException) {
                        hasSentEmail = false
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.support_contact_no_email_app)
                        )
                    }
                }

                is SupportContactFormViewModel.Event.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }

                SupportContactFormViewModel.Event.ShowShortRecordingWarning -> {
                    dialog = ContactFormDialog.ShortRecordingWarning
                }
            }
        }
    }

    when (val d = dialog) {
        is ContactFormDialog.SentConfirm -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.support_contact_sent_title)) },
                text = { Text(stringResource(R.string.support_contact_sent_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        vm.confirmSent()
                    }) {
                        Text(stringResource(eu.darken.butler.common.R.string.general_done_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) {
                        Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                    }
                },
            )
        }

        is ContactFormDialog.ShortRecordingWarning -> {
            ShortRecordingDialog(
                onKeepRecording = { dialog = null },
                onStopAnyway = {
                    dialog = null
                    vm.forceStopRecording()
                },
            )
        }

        is ContactFormDialog.DeleteReport -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.support_debuglog_session_delete_title)) },
                text = { Text(stringResource(R.string.support_debuglog_session_delete_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        vm.deleteReport(d.reportId)
                    }) {
                        Text(stringResource(eu.darken.butler.common.R.string.general_delete_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) {
                        Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                    }
                },
            )
        }

        null -> {}
    }

    val state by vm.state.collectAsState(initial = null)

    state?.let { currentState ->
        SupportContactFormScreen(
            state = currentState,
            snackbarHostState = snackbarHostState,
            onNavigateUp = { vm.navUp() },
            onCategorySelected = { vm.updateCategory(it) },
            onWorkspaceTypeSelected = { vm.updateWorkspaceType(it) },
            onDescriptionChanged = { vm.updateDescription(it) },
            onExpectedBehaviorChanged = { vm.updateExpectedBehavior(it) },
            onSelectReport = { vm.selectReport(it) },
            onDeleteReport = { id -> dialog = ContactFormDialog.DeleteReport(id) },
            onStartRecording = { vm.startRecording() },
            onStopRecording = { vm.stopRecording() },
            onSend = { vm.send() },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportContactFormScreen(
    state: SupportContactFormViewModel.State,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onNavigateUp: () -> Unit = {},
    onCategorySelected: (SupportContactFormViewModel.Category) -> Unit = {},
    onWorkspaceTypeSelected: (SupportContactFormViewModel.WorkspaceType) -> Unit = {},
    onDescriptionChanged: (String) -> Unit = {},
    onExpectedBehaviorChanged: (String) -> Unit = {},
    onSelectReport: (String) -> Unit = {},
    onDeleteReport: (String) -> Unit = {},
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onSend: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.support_contact_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action),
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Category chips
            item {
                Text(
                    text = stringResource(R.string.support_contact_category_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SupportContactFormViewModel.Category.entries.forEach { category ->
                        ButlerChip(
                            label = stringResource(category.labelRes),
                            selected = state.category == category,
                            onClick = { onCategorySelected(category) },
                            size = ButlerChipSize.Large,
                        )
                    }
                }
            }

            // 2. Bug report attachment section (bugs only)
            item {
                AnimatedVisibility(visible = state.isBug) {
                    Column {
                        Text(
                            text = stringResource(R.string.support_contact_debuglog_label),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.support_contact_debuglog_picker_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (state.reports.isEmpty() && !state.isRecording) {
                                    Text(
                                        text = stringResource(R.string.support_contact_debuglog_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                                    )
                                }

                                state.reports.forEach { info ->
                                    val isSelected = state.selectedReportId == info.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { onSelectReport(info.id) },
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 4.dp),
                                        ) {
                                            Text(
                                                text = reportTitle(info),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = formatRelativeTime(info.report.createdAt),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { onDeleteReport(info.id) }) {
                                            Icon(
                                                imageVector = Icons.TwoTone.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    if (state.isRecording) {
                                        FilledTonalButton(onClick = onStopRecording) {
                                            Icon(
                                                imageVector = Icons.TwoTone.Cancel,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.debug_log_stop_action))
                                        }
                                    } else {
                                        FilledTonalButton(onClick = onStartRecording) {
                                            Icon(
                                                imageVector = Icons.TwoTone.BugReport,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.debug_log_record_full_action))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Workspace type chips ("Related area")
            item {
                Text(
                    text = stringResource(R.string.support_contact_workspace_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SupportContactFormViewModel.WorkspaceType.entries.forEach { wsType ->
                        ButlerChip(
                            label = stringResource(wsType.labelRes),
                            selected = state.workspaceType == wsType,
                            onClick = { onWorkspaceTypeSelected(wsType) },
                            size = ButlerChipSize.Large,
                        )
                    }
                }
            }

            // 4. Description field
            item {
                val descWordColor = when {
                    state.descriptionWords == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                    state.descriptionWords < SupportContactFormViewModel.MIN_DESCRIPTION_WORDS -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                OutlinedTextField(
                    value = state.description,
                    onValueChange = onDescriptionChanged,
                    label = { Text(stringResource(R.string.support_contact_description_label)) },
                    placeholder = { Text(stringResource(state.category.descriptionHintRes)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    supportingText = {
                        Text(
                            text = stringResource(
                                R.string.support_contact_word_count,
                                SupportContactFormViewModel.MIN_DESCRIPTION_WORDS,
                            ),
                            color = descWordColor,
                        )
                    },
                )
            }

            // 5. Expected behavior (bugs only)
            item {
                AnimatedVisibility(visible = state.isBug) {
                    Column {
                        val expWordColor = when {
                            state.expectedWords == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                            state.expectedWords < SupportContactFormViewModel.MIN_EXPECTED_WORDS -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        OutlinedTextField(
                            value = state.expectedBehavior,
                            onValueChange = onExpectedBehaviorChanged,
                            label = { Text(stringResource(R.string.support_contact_expected_label)) },
                            placeholder = { Text(stringResource(R.string.support_contact_expected_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 8,
                            supportingText = {
                                Text(
                                    text = stringResource(
                                        R.string.support_contact_word_count,
                                        SupportContactFormViewModel.MIN_EXPECTED_WORDS,
                                    ),
                                    color = expWordColor,
                                )
                            },
                        )
                    }
                }
            }

            // 6. Welcome card
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.TwoTone.ContactSupport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = stringResource(R.string.support_contact_welcome),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 7. Send button
            item {
                Button(
                    onClick = onSend,
                    enabled = state.canSend,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.TwoTone.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.support_contact_send_action))
                }
            }

            // 8. Footer text
            item {
                Text(
                    text = stringResource(R.string.support_contact_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun reportTitle(info: BugReportInfo): String {
    info.report.label?.let { return it }
    val typeLabel = when (info.report.type) {
        BugReport.Type.CRASH -> stringResource(R.string.support_contact_report_type_crash)
        BugReport.Type.REPORTED -> stringResource(R.string.support_contact_report_type_reported)
        BugReport.Type.RECORDING -> stringResource(R.string.support_contact_report_type_recording)
    }
    val detail = info.report.errorClass?.substringAfterLast('.')
    return if (detail.isNullOrBlank()) typeLabel else "$typeLabel · $detail"
}

private val SupportContactFormViewModel.Category.labelRes: Int
    get() = when (this) {
        SupportContactFormViewModel.Category.QUESTION -> R.string.support_contact_category_question
        SupportContactFormViewModel.Category.FEATURE -> R.string.support_contact_category_feature
        SupportContactFormViewModel.Category.BUG -> R.string.support_contact_category_bug
    }

private val SupportContactFormViewModel.Category.descriptionHintRes: Int
    get() = when (this) {
        SupportContactFormViewModel.Category.QUESTION -> R.string.support_contact_description_question_hint
        SupportContactFormViewModel.Category.FEATURE -> R.string.support_contact_description_feature_hint
        SupportContactFormViewModel.Category.BUG -> R.string.support_contact_description_bug_hint
    }

private val SupportContactFormViewModel.WorkspaceType.labelRes: Int
    get() = when (this) {
        SupportContactFormViewModel.WorkspaceType.GENERAL -> R.string.support_contact_workspace_general
        SupportContactFormViewModel.WorkspaceType.EXPLORER -> R.string.support_contact_workspace_explorer
        SupportContactFormViewModel.WorkspaceType.SEARCHER -> R.string.support_contact_workspace_searcher
        SupportContactFormViewModel.WorkspaceType.EDITOR -> R.string.support_contact_workspace_editor
    }

private fun previewReport(
    id: String,
    type: BugReport.Type,
    createdAt: Long,
    errorClass: String?,
    label: String? = null,
) = BugReportInfo(
    report = BugReport(
        id = id,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        type = type,
        errorClass = errorClass,
        appVersion = "1.0",
        deviceFingerprint = "fp",
        apiLevel = "34",
        flavor = "FOSS",
        buildType = "DEBUG",
        installId = "id",
        locale = "en",
        label = label,
    ),
    isSeen = true,
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SupportContactFormQuestionPreview() {
    SupportContactFormScreen(
        state = SupportContactFormViewModel.State(
            category = SupportContactFormViewModel.Category.QUESTION,
            description = "I have a question about how workspaces work in Butler and how I can use them to improve my workflow significantly",
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SupportContactFormBugPreview() {
    SupportContactFormScreen(
        state = SupportContactFormViewModel.State(
            category = SupportContactFormViewModel.Category.BUG,
            workspaceType = SupportContactFormViewModel.WorkspaceType.EXPLORER,
            description = "When I navigate to a folder with many files and then try to scroll the file list crashes and I have to restart the app completely to continue using it",
            expectedBehavior = "The file list should scroll smoothly without crashing even with many files in the directory",
            reports = listOf(
                previewReport("crash_1705312200000_abcd1234", BugReport.Type.CRASH, 1705312200000L, "java.lang.IllegalStateException"),
                previewReport(
                    "recording_1705221300000_efgh5678",
                    BugReport.Type.RECORDING,
                    1705221300000L,
                    null,
                    label = "Copy stalls on SD card",
                ),
            ),
            selectedReportId = "crash_1705312200000_abcd1234",
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SupportContactFormEmptyPreview() {
    SupportContactFormScreen(
        state = SupportContactFormViewModel.State(),
    )
}

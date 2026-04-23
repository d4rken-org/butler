package eu.darken.butler.main.ui.settings.support.contactform

import android.content.ActivityNotFoundException
import android.text.format.DateUtils
import android.text.format.Formatter
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.recorder.core.DebugSession
import eu.darken.butler.common.debug.recorder.ui.ShortRecordingDialog
import eu.darken.butler.common.debug.recorder.ui.result.RecorderConsentDialog
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import java.io.File
import kotlin.time.Instant

private sealed interface ContactFormDialog {
    data object SentConfirm : ContactFormDialog
    data object Consent : ContactFormDialog
    data object ShortRecordingWarning : ContactFormDialog
    data class DeleteSession(val sessionId: String) : ContactFormDialog
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
        vm.refreshSessions()
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

                SupportContactFormViewModel.Event.ShowConsentDialog -> {
                    dialog = ContactFormDialog.Consent
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

        is ContactFormDialog.Consent -> {
            RecorderConsentDialog(
                onDismissRequest = { dialog = null },
                onConfirm = {
                    dialog = null
                    vm.doStartRecording()
                },
                onOpenPrivacyPolicy = { vm.openPrivacyPolicy() },
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

        is ContactFormDialog.DeleteSession -> {
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.support_debuglog_session_delete_title)) },
                text = { Text(stringResource(R.string.support_debuglog_session_delete_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        vm.deleteLogSession(d.sessionId)
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
            onSelectSession = { vm.selectLogSession(it) },
            onDeleteSession = { id -> dialog = ContactFormDialog.DeleteSession(id) },
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
    onSelectSession: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onSend: () -> Unit = {},
) {
    val context = LocalContext.current

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

            // 2. Debug log section (bugs only)
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
                                if (state.sessions.isEmpty() && !state.isRecording) {
                                    Text(
                                        text = stringResource(R.string.support_contact_debuglog_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                                    )
                                }

                                state.sessions.forEach { session ->
                                    val isSelected = state.selectedSessionId == session.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { onSelectSession(session.id) },
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 4.dp),
                                        ) {
                                            Text(
                                                text = session.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                            )
                                            val sizeText = Formatter.formatShortFileSize(context, session.diskSize)
                                            val agoText = DateUtils.getRelativeTimeSpanString(
                                                session.createdAt.toEpochMilliseconds(),
                                                System.currentTimeMillis(),
                                                DateUtils.SECOND_IN_MILLIS,
                                                DateUtils.FORMAT_ABBREV_RELATIVE,
                                            )
                                            Text(
                                                text = "$sizeText · $agoText",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(onClick = { onDeleteSession(session.id) }) {
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
            sessions = listOf(
                DebugSession.Ready(
                    id = "cache:eu.darken.butler_100_2024-01-15_10-30-00-000",
                    displayName = "eu.darken.butler_100_2024-01-15_10-30-00-000",
                    createdAt = Instant.fromEpochMilliseconds(1705312200000L),
                    diskSize = 1_200_000L,
                    logDir = null,
                    zipFile = File("/cache/debug/logs/eu.darken.butler_100_2024-01-15_10-30-00-000.zip"),
                    compressedSize = 1_200_000L,
                ),
                DebugSession.Ready(
                    id = "cache:eu.darken.butler_100_2024-01-14_09-15-00-000",
                    displayName = "eu.darken.butler_100_2024-01-14_09-15-00-000",
                    createdAt = Instant.fromEpochMilliseconds(1705221300000L),
                    diskSize = 800_000L,
                    logDir = null,
                    zipFile = File("/cache/debug/logs/eu.darken.butler_100_2024-01-14_09-15-00-000.zip"),
                    compressedSize = 800_000L,
                ),
            ),
            selectedSessionId = "cache:eu.darken.butler_100_2024-01-15_10-30-00-000",
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

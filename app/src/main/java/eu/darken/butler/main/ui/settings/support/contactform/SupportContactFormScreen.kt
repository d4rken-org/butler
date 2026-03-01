package eu.darken.butler.main.ui.settings.support.contactform

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.Send
import androidx.compose.material.icons.automirrored.twotone.ContactSupport
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FiberManualRecord
import androidx.compose.material.icons.twotone.Stop
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.recorder.ui.result.RecorderConsentDialog
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.ui.waitForState
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SupportContactFormScreenHost(
    vm: SupportContactFormViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm.emailEvent) {
        vm.emailEvent.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.support_contact_no_email_app))
                }
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        vm.scanLogSessions()
        onPauseOrDispose {}
    }

    val state by waitForState(vm.state)

    state?.let { currentState ->
        SupportContactFormScreen(
            state = currentState,
            snackbarHostState = snackbarHostState,
            onNavigateUp = { vm.navUp() },
            onCategorySelected = { vm.updateCategory(it) },
            onWorkspaceTypeSelected = { vm.updateWorkspaceType(it) },
            onDescriptionChanged = { vm.updateDescription(it) },
            onExpectedBehaviorChanged = { vm.updateExpectedBehavior(it) },
            onToggleRecording = { vm.toggleRecording() },
            onSelectLogSession = { vm.selectLogSession(it) },
            onDeleteLogSession = { vm.deleteLogSession(it) },
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
    onToggleRecording: () -> Unit = {},
    onSelectLogSession: (File?) -> Unit = {},
    onDeleteLogSession: (File) -> Unit = {},
    onSend: () -> Unit = {},
) {
    var showConsentDialog by remember { mutableStateOf(false) }

    if (showConsentDialog) {
        RecorderConsentDialog(
            onDismissRequest = { showConsentDialog = false },
            onConfirm = {
                showConsentDialog = false
                onToggleRecording()
            },
            onOpenPrivacyPolicy = {},
        )
    }

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
                            selected = state.form.category == category,
                            onClick = { onCategorySelected(category) },
                            size = ButlerChipSize.Large,
                        )
                    }
                }
            }

            // 2. Debug log section (bugs only) — moved up from bottom
            item {
                AnimatedVisibility(visible = state.form.category == SupportContactFormViewModel.Category.BUG) {
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

                        TextButton(onClick = {
                            if (state.logPicker.isRecording) {
                                onToggleRecording()
                            } else {
                                showConsentDialog = true
                            }
                        }) {
                            Icon(
                                imageVector = if (state.logPicker.isRecording) {
                                    Icons.TwoTone.Stop
                                } else {
                                    Icons.TwoTone.FiberManualRecord
                                },
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (state.logPicker.isRecording) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state.logPicker.isRecording) {
                                    stringResource(R.string.debug_log_stop_action)
                                } else {
                                    stringResource(R.string.debug_log_record_action)
                                }
                            )
                        }

                        if (state.logPicker.sessions.isEmpty()) {
                            Text(
                                text = stringResource(R.string.support_contact_debuglog_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                            )
                        }
                    }
                }
            }

            if (state.form.category == SupportContactFormViewModel.Category.BUG &&
                state.logPicker.sessions.isNotEmpty()
            ) {
                items(state.logPicker.sessions, key = { it.absolutePath }) { zipFile ->
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.logPicker.selectedZip == zipFile,
                            onClick = {
                                onSelectLogSession(
                                    if (state.logPicker.selectedZip == zipFile) null else zipFile
                                )
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = zipFile.nameWithoutExtension,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                text = Formatter.formatShortFileSize(context, zipFile.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDeleteLogSession(zipFile) }) {
                            Icon(
                                imageVector = Icons.TwoTone.Delete,
                                contentDescription = stringResource(R.string.support_debuglog_delete_action),
                            )
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
                            selected = state.form.workspaceType == wsType,
                            onClick = { onWorkspaceTypeSelected(wsType) },
                            size = ButlerChipSize.Large,
                        )
                    }
                }
            }

            // 4. Description field (with category-specific hint)
            item {
                val descWordCount = SupportContactFormViewModel.wordCount(state.form.description)
                val descColor = when {
                    descWordCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                    descWordCount < 20 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
                OutlinedTextField(
                    value = state.form.description,
                    onValueChange = onDescriptionChanged,
                    label = { Text(stringResource(R.string.support_contact_description_label)) },
                    placeholder = { Text(stringResource(state.form.category.descriptionHintRes)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    supportingText = {
                        Text(
                            text = stringResource(R.string.support_contact_word_count, descWordCount, 20),
                            color = descColor,
                        )
                    },
                )
            }

            // 5. Expected behavior (bugs only)
            item {
                AnimatedVisibility(visible = state.form.category == SupportContactFormViewModel.Category.BUG) {
                    Column {
                        val expWordCount = SupportContactFormViewModel.wordCount(state.form.expectedBehavior)
                        val expColor = when {
                            expWordCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                            expWordCount < 10 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                        OutlinedTextField(
                            value = state.form.expectedBehavior,
                            onValueChange = onExpectedBehaviorChanged,
                            label = { Text(stringResource(R.string.support_contact_expected_label)) },
                            placeholder = { Text(stringResource(R.string.support_contact_expected_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 8,
                            supportingText = {
                                Text(
                                    text = stringResource(R.string.support_contact_word_count, expWordCount, 10),
                                    color = expColor,
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

            // 7. Send button ("Open email app")
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
@Composable
private fun SupportContactFormQuestionPreview() {
    PreviewWrapper {
        SupportContactFormScreen(
            state = SupportContactFormViewModel.State(
                form = SupportContactFormViewModel.FormState(
                    category = SupportContactFormViewModel.Category.QUESTION,
                    description = "I have a question about how workspaces work in Butler and how I can use them to improve my workflow significantly",
                ),
                canSend = true,
            ),
        )
    }
}

@Preview2
@Composable
private fun SupportContactFormBugPreview() {
    PreviewWrapper {
        SupportContactFormScreen(
            state = SupportContactFormViewModel.State(
                form = SupportContactFormViewModel.FormState(
                    category = SupportContactFormViewModel.Category.BUG,
                    workspaceType = SupportContactFormViewModel.WorkspaceType.EXPLORER,
                    description = "When I navigate to a folder with many files and then try to scroll the file list crashes and I have to restart the app completely to continue using it",
                    expectedBehavior = "The file list should scroll smoothly without crashing even with many files in the directory",
                ),
                logPicker = SupportContactFormViewModel.LogPickerState(
                    sessions = listOf(
                        File("/cache/debug/logs/eu.darken.butler_100_2024-01-15_10-30-00-000.zip"),
                        File("/cache/debug/logs/eu.darken.butler_100_2024-01-14_09-15-00-000.zip"),
                    ),
                    selectedZip = File("/cache/debug/logs/eu.darken.butler_100_2024-01-15_10-30-00-000.zip"),
                ),
                canSend = true,
            ),
        )
    }
}

@Preview2
@Composable
private fun SupportContactFormEmptyPreview() {
    PreviewWrapper {
        SupportContactFormScreen(
            state = SupportContactFormViewModel.State(),
        )
    }
}

package eu.darken.butler.editor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.twotone.FormatListNumbered
import androidx.compose.material.icons.twotone.FormatSize
import androidx.compose.material.icons.twotone.Palette
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material.icons.twotone.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.common.ui.SecondsDurationInputDialog
import androidx.compose.runtime.collectAsState
import eu.darken.butler.editor.R
import kotlin.time.Duration.Companion.seconds

@Composable
fun EditorSettingsScreen(
    state: EditorSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onShowLineNumbersChange: (Boolean) -> Unit,
    onWordWrapChange: (Boolean) -> Unit,
    onSyntaxHighlightingChange: (Boolean) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onTabSizeChange: (Int) -> Unit,
    onAutoSaveEnabledChange: (Boolean) -> Unit,
    onAutoSaveIntervalChange: (Int) -> Unit,
) {
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showTabSizeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                eu.darken.butler.common.R.string.general_back_action
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
                SettingsSwitchItem(
                    icon = Icons.TwoTone.FormatListNumbered,
                    title = stringResource(R.string.editor_settings_show_line_numbers_title),
                    subtitle = stringResource(R.string.editor_settings_show_line_numbers_subtitle),
                    checked = state.showLineNumbers,
                    onCheckedChange = onShowLineNumbersChange
                )
                SettingsDivider()
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.AutoMirrored.Filled.WrapText,
                    title = stringResource(R.string.editor_settings_word_wrap_title),
                    subtitle = stringResource(R.string.editor_settings_word_wrap_subtitle),
                    checked = state.wordWrap,
                    onCheckedChange = onWordWrapChange
                )
                SettingsDivider()
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Palette,
                    title = stringResource(R.string.editor_settings_syntax_highlighting_title),
                    subtitle = stringResource(R.string.editor_settings_syntax_highlighting_subtitle),
                    checked = state.syntaxHighlighting,
                    onCheckedChange = onSyntaxHighlightingChange
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.FormatSize,
                    title = stringResource(R.string.editor_settings_font_size_title),
                    subtitle = stringResource(R.string.editor_settings_font_size_subtitle),
                    value = stringResource(R.string.editor_settings_font_size_value, state.fontSize),
                    onClick = { showFontSizeDialog = true },
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.AutoMirrored.Filled.KeyboardTab,
                    title = stringResource(R.string.editor_settings_tab_size_title),
                    subtitle = stringResource(R.string.editor_settings_tab_size_subtitle),
                    value = state.tabSize.toString(),
                    onClick = { showTabSizeDialog = true },
                )
            }

            item {
                SettingsCategoryHeader(text = stringResource(R.string.editor_settings_autosave_category))
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Save,
                    title = stringResource(R.string.editor_settings_autosave_enabled_title),
                    subtitle = stringResource(R.string.editor_settings_autosave_enabled_subtitle),
                    checked = state.autoSaveEnabled,
                    onCheckedChange = onAutoSaveEnabledChange
                )
                SettingsDivider()
            }

            item {
                val intervalValue = context.resources.getQuantityString(
                    eu.darken.butler.common.R.plurals.common_duration_seconds_full,
                    state.autoSaveIntervalSeconds,
                    state.autoSaveIntervalSeconds,
                )
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Timer,
                    title = stringResource(R.string.editor_settings_autosave_interval_title),
                    subtitle = stringResource(R.string.editor_settings_autosave_interval_subtitle),
                    value = intervalValue,
                    onClick = { showIntervalDialog = true },
                    enabled = state.autoSaveEnabled,
                )
            }
        }
    }

    if (showFontSizeDialog) {
        IntSelectorDialog(
            title = stringResource(R.string.editor_settings_font_size_title),
            options = EditorSettingsViewModel.FONT_SIZE_OPTIONS,
            selected = state.fontSize,
            optionLabel = { stringResource(R.string.editor_settings_font_size_value, it) },
            onSelected = {
                onFontSizeChange(it)
                showFontSizeDialog = false
            },
            onDismiss = { showFontSizeDialog = false },
        )
    }

    if (showTabSizeDialog) {
        IntSelectorDialog(
            title = stringResource(R.string.editor_settings_tab_size_title),
            options = EditorSettingsViewModel.TAB_SIZE_OPTIONS,
            selected = state.tabSize,
            optionLabel = { it.toString() },
            onSelected = {
                onTabSizeChange(it)
                showTabSizeDialog = false
            },
            onDismiss = { showTabSizeDialog = false },
        )
    }

    if (showIntervalDialog) {
        SecondsDurationInputDialog(
            title = stringResource(R.string.editor_settings_autosave_interval_dialog_title),
            currentDuration = state.autoSaveIntervalSeconds.seconds,
            minimumDuration = 10.seconds,
            maximumDuration = 300.seconds,
            defaultDuration = 30.seconds,
            onDismiss = { showIntervalDialog = false },
            onConfirm = { duration ->
                onAutoSaveIntervalChange(duration.inWholeSeconds.toInt())
                showIntervalDialog = false
            },
        )
    }
}

@Composable
private fun IntSelectorDialog(
    title: String,
    options: List<Int>,
    selected: Int,
    optionLabel: @Composable (Int) -> String,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelected(option) },
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(text = optionLabel(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorSettingsScreenPreview() {
    EditorSettingsScreen(
        state = EditorSettingsViewModel.State(
            showLineNumbers = true,
            wordWrap = false,
            autoSaveEnabled = true,
            autoSaveIntervalSeconds = 30,
        ),
        onNavigateUp = {},
        onShowLineNumbersChange = {},
        onWordWrapChange = {},
        onSyntaxHighlightingChange = {},
        onFontSizeChange = {},
        onTabSizeChange = {},
        onAutoSaveEnabledChange = {},
        onAutoSaveIntervalChange = {},
    )
}

@Composable
fun EditorSettingsScreenHost(vm: EditorSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { vmState ->
        EditorSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onShowLineNumbersChange = { vm.updateShowLineNumbers(it) },
            onWordWrapChange = { vm.updateWordWrap(it) },
            onSyntaxHighlightingChange = { vm.updateSyntaxHighlighting(it) },
            onFontSizeChange = { vm.updateFontSize(it) },
            onTabSizeChange = { vm.updateTabSize(it) },
            onAutoSaveEnabledChange = { vm.updateAutoSaveEnabled(it) },
            onAutoSaveIntervalChange = { vm.updateAutoSaveInterval(it) },
        )
    }
}

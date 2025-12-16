package eu.darken.butler.main.ui.settings.general

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Notifications
import androidx.compose.material.icons.twotone.Palette
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material.icons.twotone.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.EnumSelectorDialog
import eu.darken.butler.common.settings.ThemeColorSelectorDialog
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.settings.SettingsSwitchItem
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.common.ui.waitForState
import kotlinx.coroutines.launch

@Composable
fun GeneralSettingsScreen(
    state: GeneralSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onLanguageSwitcher: (() -> Unit)?,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onThemeStyleSelected: (ThemeStyle) -> Unit,
    onThemeColorSelected: (ThemeColor) -> Unit,
    onUpgradeButler: () -> Unit,
    onUpdateCheckEnabledChange: (Boolean) -> Unit,
    onMotdEnabledChange: (Boolean) -> Unit,
    onConfirmExitEnabledChange: (Boolean) -> Unit,
    onDocumentsProviderEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showThemeModeDialog by remember { mutableStateOf(false) }
    var showThemeStyleDialog by remember { mutableStateOf(false) }
    var showThemeColorDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.general_settings_label)) },
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
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_ui_label))
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Palette,
                    title = stringResource(R.string.ui_theme_mode_setting_label),
                    subtitle = stringResource(R.string.ui_theme_mode_setting_explanation),
                    value = state.themeState.mode.label.get(context),
                    onClick = {
                        if (state.isUpgraded) {
                            showThemeModeDialog = true
                        } else {
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.upgrade_feature_requires_pro),
                                    actionLabel = context.getString(R.string.upgrade_prompt_upgrade_action)
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    onUpgradeButler()
                                }
                            }
                        }
                    }
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Palette,
                    title = stringResource(R.string.ui_theme_style_setting_label),
                    subtitle = stringResource(R.string.ui_theme_style_setting_explanation),
                    value = state.themeState.style.label.get(context),
                    onClick = {
                        if (state.isUpgraded) {
                            showThemeStyleDialog = true
                        } else {
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.upgrade_feature_requires_pro),
                                    actionLabel = context.getString(R.string.upgrade_prompt_upgrade_action)
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    onUpgradeButler()
                                }
                            }
                        }
                    }
                )
                SettingsDivider()
            }

            item {
                val isMaterialYouActive = state.themeState.style == ThemeStyle.MATERIAL_YOU && hasApiLevel(31)
                
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Palette,
                    title = stringResource(R.string.ui_theme_color_setting_label),
                    subtitle = if (isMaterialYouActive) {
                        stringResource(R.string.ui_theme_color_setting_disabled_materialyou)
                    } else {
                        stringResource(R.string.ui_theme_color_setting_explanation)
                    },
                    value = if (isMaterialYouActive) {
                        stringResource(R.string.ui_theme_color_value_system)
                    } else {
                        state.themeState.color.label.get(context)
                    },
                    enabled = !isMaterialYouActive,
                    onClick = {
                        if (state.isUpgraded) {
                            showThemeColorDialog = true
                        } else {
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.upgrade_feature_requires_pro),
                                    actionLabel = context.getString(R.string.upgrade_prompt_upgrade_action)
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    onUpgradeButler()
                                }
                            }
                        }
                    }
                )
                SettingsDivider()
            }

            onLanguageSwitcher?.let { action ->
                item {
                    SettingsPreferenceItem(
                        icon = Icons.TwoTone.Translate,
                        title = stringResource(R.string.ui_language_override_label),
                        subtitle = stringResource(R.string.ui_language_override_desc),
                        onClick = { action.invoke() }
                    )
                    SettingsDivider()
                }
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Notifications,
                    title = stringResource(R.string.confirm_exit_setting_title),
                    subtitle = stringResource(R.string.confirm_exit_setting_description),
                    checked = state.confirmExitEnabled,
                    onCheckedChange = onConfirmExitEnabledChange
                )
                SettingsDivider()
            }

            item {
                SettingsCategoryHeader(
                    text = stringResource(R.string.settings_category_integration_label)
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.FolderOpen,
                    title = stringResource(eu.darken.butler.provider.documents.R.string.provider_documents_enabled_title),
                    subtitle = stringResource(eu.darken.butler.provider.documents.R.string.provider_documents_enabled_desc),
                    checked = state.isDocumentsProviderEnabled,
                    onCheckedChange = onDocumentsProviderEnabledChange
                )
            }

            item {
                SettingsCategoryHeader(
                    text = stringResource(R.string.settings_category_other_label)
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Update,
                    title = stringResource(R.string.updater_check_enabled_setting_title),
                    subtitle =
                        stringResource(R.string.updater_check_enabled_setting_description),
                    checked = state.updateCheckEnabled,
                    onCheckedChange = onUpdateCheckEnabledChange
                )
                SettingsDivider()
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.TwoTone.Notifications,
                    title = stringResource(R.string.motd_check_enabled_setting_title),
                    subtitle = stringResource(R.string.motd_check_enabled_setting_description),
                    checked = state.motdEnabled,
                    onCheckedChange = onMotdEnabledChange
                )
            }
        }
    }

    if (showThemeModeDialog) {
        EnumSelectorDialog(
            title = stringResource(R.string.ui_theme_mode_setting_label),
            options = ThemeMode.entries,
            selectedOption = state.themeState.mode,
            onOptionSelected = { mode ->
                onThemeModeSelected(mode)
                showThemeModeDialog = false
            },
            onDismiss = { showThemeModeDialog = false }
        )
    }

    if (showThemeStyleDialog) {
        EnumSelectorDialog(
            title = stringResource(R.string.ui_theme_style_setting_label),
            options = ThemeStyle.entries,
            selectedOption = state.themeState.style,
            onOptionSelected = { style ->
                onThemeStyleSelected(style)
                showThemeStyleDialog = false
            },
            onDismiss = { showThemeStyleDialog = false }
        )
    }

    if (showThemeColorDialog) {
        ThemeColorSelectorDialog(
            title = stringResource(R.string.ui_theme_color_setting_label),
            selectedOption = state.themeState.color,
            onOptionSelected = { color ->
                onThemeColorSelected(color)
                showThemeColorDialog = false
            },
            onDismiss = { showThemeColorDialog = false }
        )
    }
}

@Preview2
@Composable
private fun GeneralSettingsScreenPreview() {
    PreviewWrapper {
        GeneralSettingsScreen(
            state = GeneralSettingsViewModel.State(),
            onNavigateUp = {},
            onLanguageSwitcher = {},
            onThemeModeSelected = {},
            onThemeStyleSelected = {},
            onThemeColorSelected = {},
            onUpdateCheckEnabledChange = {},
            onMotdEnabledChange = {},
            onConfirmExitEnabledChange = {},
            onDocumentsProviderEnabledChange = {},
            onUpgradeButler = {},
        )
    }
}

@Composable
fun GeneralSettingsScreenHost(vm: GeneralSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { vmState ->
        GeneralSettingsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onLanguageSwitcher = { vm.showLanguagePicker() },
            onThemeModeSelected = { vm.updateThemeMode(it) },
            onThemeStyleSelected = { vm.updateThemeStyle(it) },
            onThemeColorSelected = { vm.updateThemeColor(it) },
            onUpdateCheckEnabledChange = { vm.updateUpdateCheckEnabled(it) },
            onMotdEnabledChange = { vm.updateMotdEnabled(it) },
            onConfirmExitEnabledChange = { vm.updateConfirmExitEnabled(it) },
            onDocumentsProviderEnabledChange = { vm.updateDocumentsProviderEnabled(it) },
            onUpgradeButler = { vm.upgradeButler() },
        )
    }
}

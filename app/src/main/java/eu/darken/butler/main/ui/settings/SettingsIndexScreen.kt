package eu.darken.butler.main.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.ListAlt
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.PrivacyTip
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Stars
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.settings.SettingsBaseItem
import eu.darken.butler.common.settings.SettingsCategoryHeader
import eu.darken.butler.common.settings.SettingsDivider
import androidx.compose.runtime.collectAsState
import eu.darken.butler.editor.ui.editor
import eu.darken.butler.explorer.ui.explorer
import eu.darken.butler.history.ui.history
import eu.darken.butler.searcher.ui.searcher
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon

private const val TAPS_TO_UNLOCK = 7
private const val TAPS_TO_START_COUNTDOWN = 3

@Composable
fun SettingsIndexScreenHost(vm: SettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { state ->
        SettingsIndexScreen(
            state = state,
            onNavigateUp = { vm.navUp() },
            onNavigateTo = { vm.navTo(it) },
            onOpenUrl = { vm.openUrl(it) },
            onUnlockDeveloperMode = { vm.unlockDeveloperMode() },
        )
    }
}

@Composable
fun SettingsIndexScreen(
    state: SettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onNavigateTo: (NavigationDestination) -> Unit,
    onOpenUrl: (String) -> Unit,
    onUnlockDeveloperMode: () -> Unit,
) {
    val context = LocalContext.current
    var tapCount by remember { mutableIntStateOf(0) }
    var showUnlockDialog by remember { mutableStateOf(false) }

    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = {
                showUnlockDialog = false
                tapCount = 0
            },
            title = { Text(stringResource(R.string.settings_developer_mode_unlock_title)) },
            text = { Text(stringResource(R.string.settings_developer_mode_unlock_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnlockDeveloperMode()
                        showUnlockDialog = false
                        tapCount = 0
                    }
                ) {
                    Text(stringResource(R.string.settings_developer_mode_unlock_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUnlockDialog = false
                        tapCount = 0
                    }
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.settings_label))
                        if (state.isUpgraded) {
                            ColoredTitleText(
                                fullTitle = stringResource(eu.darken.butler.common.R.string.app_name_upgraded),
                                postfix = stringResource(eu.darken.butler.common.R.string.app_name_upgrade_postfix),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                text = stringResource(eu.darken.butler.common.R.string.app_name),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action)
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
                SettingsBaseItem(
                    icon = Icons.TwoTone.Settings,
                    title = stringResource(R.string.general_settings_label),
                    subtitle = stringResource(R.string.general_settings_desc),
                    onClick = { onNavigateTo(Nav.Settings.general()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.Tune,
                    title = stringResource(R.string.setup_title),
                    subtitle = stringResource(R.string.setup_settings_description),
                    onClick = { onNavigateTo(Nav.Main.destSetup(showCompleted = true)) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.Workspaces,
                    title = stringResource(eu.darken.butler.workspace.R.string.workspace_settings_title),
                    subtitle = stringResource(R.string.workspace_settings_subtitle),
                    onClick = { onNavigateTo(Nav.Settings.workspaces()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.ContentPaste,
                    title = stringResource(eu.darken.butler.workspace.R.string.clipboard_settings_title),
                    subtitle = stringResource(R.string.clipboard_settings_subtitle),
                    onClick = { onNavigateTo(Nav.Settings.clipboard()) },
                )
                SettingsDivider()
            }

            item { SettingsCategoryHeader(stringResource(R.string.settings_category_tools_label)) }

            item {
                SettingsBaseItem(
                    icon = Workspace.Type.EXPLORER.icon,
                    title = stringResource(eu.darken.butler.explorer.R.string.explorer_settings_title),
                    subtitle = stringResource(R.string.explorer_settings_subtitle),
                    onClick = { onNavigateTo(Nav.Settings.explorer()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Workspace.Type.SEARCHER.icon,
                    title = stringResource(eu.darken.butler.searcher.R.string.searcher_settings_title),
                    subtitle = stringResource(R.string.searcher_settings_subtitle),
                    onClick = { onNavigateTo(Nav.Settings.searcher()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Workspace.Type.EDITOR.icon,
                    title = stringResource(eu.darken.butler.editor.R.string.editor_settings_title),
                    subtitle = stringResource(R.string.editor_settings_subtitle),
                    onClick = { onNavigateTo(Nav.Settings.editor()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Workspace.Type.HISTORY.icon,
                    title = stringResource(eu.darken.butler.history.R.string.history_settings_title),
                    subtitle = stringResource(R.string.history_settings_subtitle),
                    onClick = { onNavigateTo(Nav.Settings.history()) },
                )
                SettingsDivider()
            }

            item { SettingsCategoryHeader(stringResource(R.string.settings_category_other_label)) }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.Stars,
                    title = stringResource(R.string.settings_upgrade_status_label),
                    subtitle = when {
                        state.isUpgraded && state.upgradeType == UpgradeRepo.Type.GPLAY ->
                            stringResource(R.string.upgrade_status_pro)
                        state.isUpgraded && state.upgradeType == UpgradeRepo.Type.FOSS ->
                            stringResource(R.string.upgrade_status_foss)
                        else -> stringResource(R.string.upgrade_status_free)
                    },
                    onClick = { onNavigateTo(Nav.Settings.upgradeStatus()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.Info,
                    title = stringResource(R.string.settings_support_label),
                    subtitle = stringResource(R.string.settings_support_description),
                    onClick = { onNavigateTo(Nav.Settings.support()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.AutoMirrored.TwoTone.ListAlt,
                    title = stringResource(R.string.changelog_label),
                    subtitle = state.versionText,
                    onClick = { onOpenUrl(ButlerLinks.CHANGELOG) },
                    onLongClick = if (state.canUnlockDeveloperMode) {
                        {
                            tapCount++
                            when {
                                tapCount >= TAPS_TO_UNLOCK -> showUnlockDialog = true
                                tapCount >= TAPS_TO_START_COUNTDOWN -> {
                                    val remaining = TAPS_TO_UNLOCK - tapCount
                                    Toast.makeText(
                                        context,
                                        context.resources.getQuantityString(
                                            R.plurals.settings_developer_mode_countdown,
                                            remaining,
                                            remaining,
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    } else null,
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.Favorite,
                    title = stringResource(R.string.settings_acknowledgements_label),
                    subtitle = stringResource(R.string.settings_acknowledgements_description),
                    onClick = { onNavigateTo(Nav.Settings.acks()) },
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.PrivacyTip,
                    title = stringResource(R.string.settings_privacy_policy_label),
                    subtitle = stringResource(R.string.settings_privacy_policy_desc),
                    onClick = { onOpenUrl(ButlerLinks.PRIVACY_POLICY) },
                )
            }

        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SettingsScreenPreview() {
    SettingsIndexScreen(
        state = SettingsViewModel.State(
            isUpgraded = true,
            upgradeType = UpgradeRepo.Type.GPLAY,
        ),
        onNavigateUp = {},
        onNavigateTo = {},
        onOpenUrl = {},
        onUnlockDeveloperMode = {},
    )
}

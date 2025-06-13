package eu.darken.butler.main.ui.settings.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.icons.Discord
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.main.ui.settings.common.SettingsCategoryHeader
import eu.darken.butler.main.ui.settings.common.SettingsDivider
import eu.darken.butler.main.ui.settings.common.SettingsPreferenceItem

@Composable
fun SupportScreen(
        onNavigateUp: () -> Unit,
        onDebugLog: () -> Unit,
        onOpenUrl: (String) -> Unit,
) {
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
                        icon = Icons.Default.Book,
                        title = stringResource(R.string.settings_support_wiki_label),
                        subtitle = stringResource(R.string.settings_support_wiki_description),
                        onClick = { onOpenUrl(ButlerLinks.WIKI) }
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                        icon = Icons.Default.BugReport,
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
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.settings_support_debuglog_label),
                        subtitle = stringResource(R.string.settings_support_debuglog_desc),
                        onClick = onDebugLog
                )
            }
        }
    }
}

@Preview2
@Composable
private fun SupportScreenPreview() {
    PreviewWrapper {
        SupportScreen(
            onNavigateUp = {},
            onDebugLog = {},
            onOpenUrl = {},
        )
    }
}

@Composable
fun SupportScreenHost(vm: SupportScreenViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    SupportScreen(
        onNavigateUp = { vm.navUp() },
        onDebugLog = { vm.debugLog() },
        onOpenUrl = { url -> vm.openUrl(url) },
    )
}

package eu.darken.butler.main.ui.settings.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.main.ui.settings.common.SettingsPreferenceItem

@Preview2
@Composable
private fun SupportScreenPreview() {
    PreviewWrapper {
        SupportScreen(
            onNavigateUp = {},
        )
    }
}

@Composable
fun SupportScreenHost(vm: SupportScreenViewModel = hiltViewModel()) {
    SupportScreen(
        onNavigateUp = { vm.goTo(null) },
    )
}

@Composable
fun SupportScreen(
    onNavigateUp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_support_label)) },
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
                SettingsPreferenceItem(
                    icon = Icons.Default.Book,
                    title = stringResource(R.string.documentation_label),
                    subtitle = "Learn how to use Butler effectively",
                    onClick = { }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                )
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.issue_tracker_label),
                    subtitle = stringResource(R.string.issue_tracker_description),
                    onClick = { }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                )
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = stringResource(R.string.discord_label),
                    subtitle = stringResource(R.string.discord_description),
                    onClick = { }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                )
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.Default.Fingerprint,
                    title = stringResource(R.string.support_installid_label),
                    subtitle = stringResource(R.string.support_installid_desc),
                    onClick = { }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                )
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.Default.Description,
                    title = stringResource(R.string.support_debuglog_label),
                    subtitle = stringResource(R.string.support_debuglog_desc),
                    onClick = { }
                )
            }
        }
    }
}
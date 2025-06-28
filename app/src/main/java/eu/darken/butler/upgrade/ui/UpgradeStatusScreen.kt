package eu.darken.butler.upgrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerIcon
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.upgrade.UpgradeRepo
import java.time.Instant

@Composable
fun UpgradeStatusScreenHost(vm: UpgradeStatusViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { state ->
        UpgradeStatusScreen(
            state = state,
            onNavigateUp = { vm.navUp() },
            onUpgradeClick = { vm.onUpgradeClick() },
        )
    }
}

@Composable
fun UpgradeStatusScreen(
    state: UpgradeStatusViewModel.State,
    onNavigateUp: () -> Unit,
    onUpgradeClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.upgrade_status_screen_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Mascot
            ButlerIcon(
                size = 120.dp,
                contentDescription = stringResource(eu.darken.butler.common.R.string.butler_mascot_description)
            )

            // App name
            if (state.isUpgraded) {
                val postfix = when (state.upgradeType) {
                    UpgradeRepo.Type.GPLAY -> stringResource(eu.darken.butler.common.R.string.app_name_upgrade_postfix)
                    UpgradeRepo.Type.FOSS -> stringResource(eu.darken.butler.common.R.string.app_name_upgrade_postfix)
                }
                ColoredTitleText(
                    fullTitle = stringResource(eu.darken.butler.common.R.string.app_name_upgraded),
                    postfix = postfix,
                    style = MaterialTheme.typography.headlineMedium,
                )
            } else {
                Text(
                    text = stringResource(eu.darken.butler.common.R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.upgrade_status_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Upgraded status with emoji
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.upgrade_status_upgraded),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (state.isUpgraded) "✅" else "❌",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    val statusText = when {
                        state.isUpgraded && state.upgradeType == UpgradeRepo.Type.GPLAY -> {
                            stringResource(R.string.upgrade_status_pro)
                        }
                        state.isUpgraded && state.upgradeType == UpgradeRepo.Type.FOSS -> {
                            stringResource(R.string.upgrade_status_foss)
                        }
                        else -> {
                            stringResource(R.string.upgrade_status_free)
                        }
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    if (state.isUpgraded && state.upgradedAtFormatted != null) {
                        Text(
                            text = stringResource(R.string.upgrade_status_upgraded_on, state.upgradedAtFormatted),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Upgrade button
            if (!state.isUpgraded) {
                Button(
                    onClick = onUpgradeClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val buttonText = when (state.upgradeType) {
                        UpgradeRepo.Type.GPLAY -> stringResource(R.string.upgrade_status_button_upgrade_pro)
                        UpgradeRepo.Type.FOSS -> stringResource(R.string.upgrade_status_button_upgrade_foss)
                    }
                    Text(text = buttonText)
                }
            }
        }
    }
}

@Preview2
@Composable
private fun UpgradeStatusScreenPreview() {
    PreviewWrapper {
        UpgradeStatusScreen(
            state = UpgradeStatusViewModel.State(
                isUpgraded = false,
                upgradeType = UpgradeRepo.Type.GPLAY,
                upgradedAt = null,
                upgradedAtFormatted = null
            ),
            onNavigateUp = {},
            onUpgradeClick = {}
        )
    }
}

@Preview2
@Composable
private fun UpgradeStatusScreenUpgradedPreview() {
    PreviewWrapper {
        UpgradeStatusScreen(
            state = UpgradeStatusViewModel.State(
                isUpgraded = true,
                upgradeType = UpgradeRepo.Type.GPLAY,
                upgradedAt = Instant.now(),
                upgradedAtFormatted = "January 15, 2024"
            ),
            onNavigateUp = {},
            onUpgradeClick = {}
        )
    }
}
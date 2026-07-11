package eu.darken.butler.upgrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.CheckCircle
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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import androidx.compose.runtime.collectAsState
import eu.darken.butler.upgrade.UpgradeRepo
import kotlin.time.Clock

@Composable
fun UpgradeStatusScreenHost(vm: UpgradeStatusViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ButlerMascot(
                modifier = Modifier.size(120.dp),
                variant = if (state.isUpgraded) ButlerMascotMode.Static.Happy() else ButlerMascotMode.Static.Sad(),
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
                    containerColor = if (state.isUpgraded) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(12.dp)
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

                    // Upgraded status with icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.upgrade_status_upgraded),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Icon(
                            imageVector = if (state.isUpgraded) Icons.TwoTone.CheckCircle else Icons.TwoTone.Cancel,
                            contentDescription = null,
                            tint = if (state.isUpgraded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
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

            // Feature highlights for non-upgraded users
            if (!state.isUpgraded) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.upgrade_benefits_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.upgrade_benefit_multitasking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.upgrade_benefit_customization),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.upgrade_benefit_extra_options),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.upgrade_benefit_motivation),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.upgrade_benefit_early_access),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.upgrade_benefit_and_more),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Upgrade button
            if (!state.isUpgraded) {
                Button(
                    onClick = onUpgradeClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    val buttonText = when (state.upgradeType) {
                        UpgradeRepo.Type.GPLAY -> stringResource(R.string.upgrade_status_button_upgrade_pro)
                        UpgradeRepo.Type.FOSS -> stringResource(R.string.upgrade_status_button_upgrade_foss)
                    }
                    Text(
                        text = buttonText,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeStatusScreenPreview() {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeStatusScreenUpgradedPreview() {
    UpgradeStatusScreen(
        state = UpgradeStatusViewModel.State(
            isUpgraded = true,
            upgradeType = UpgradeRepo.Type.GPLAY,
            upgradedAt = Clock.System.now(),
            upgradedAtFormatted = "January 15, 2024"
        ),
        onNavigateUp = {},
        onUpgradeClick = {}
    )
}
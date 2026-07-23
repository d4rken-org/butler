package eu.darken.butler.upgrade.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler

@Composable
fun UpgradeScreenHost(
    manage: Boolean,
    vm: UpgradeViewModel = hiltViewModel(
        key = "upgrade-manage-$manage",
        creationCallback = { factory: UpgradeViewModel.Factory -> factory.create(manage = manage) },
    ),
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var dialog by remember { mutableStateOf<UpgradeDialog?>(null) }

    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    val restoreSuccessMessage = stringResource(R.string.upgrade_screen_restore_success_message)
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                UpgradeEvents.RestoreFailed -> dialog = UpgradeDialog.RestoreFailed
                UpgradeEvents.RestoreSucceeded -> snackbarHostState.showSnackbar(restoreSuccessMessage)
                UpgradeEvents.SubscriptionStillRenewing -> dialog = UpgradeDialog.SubStillRenewing
                UpgradeEvents.SubscriptionCheckFailed -> dialog = UpgradeDialog.SubCheckFailed
            }
        }
    }

    state?.let { current ->
        UpgradeScreen(
            state = current,
            snackbarHostState = snackbarHostState,
            onNavigateBack = { vm.navUp() },
            onGoIap = { vm.onGoIap(context as Activity) },
            onGoSubscription = { vm.onGoSubscription(context as Activity) },
            onGoSubscriptionTrial = { vm.onGoSubscriptionTrial(context as Activity) },
            onRestorePurchase = { vm.restorePurchase() },
            onManageSubscription = { vm.onManageSubscription() },
            onRetry = { vm.retrySkuQuery() },
        )
    }

    when (dialog) {
        UpgradeDialog.RestoreFailed -> RestoreFailedDialog(onDismiss = { dialog = null })
        UpgradeDialog.SubStillRenewing -> SimpleMessageDialog(
            title = stringResource(R.string.upgrade_screen_sub_still_renewing_title),
            message = stringResource(R.string.upgrade_screen_sub_still_renewing_message),
            onDismiss = { dialog = null },
        )
        UpgradeDialog.SubCheckFailed -> SimpleMessageDialog(
            title = stringResource(R.string.upgrade_screen_sub_check_failed_title),
            message = stringResource(R.string.upgrade_screen_sub_check_failed_message),
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class UpgradeDialog { RestoreFailed, SubStillRenewing, SubCheckFailed }

@Composable
fun UpgradeScreen(
    state: UpgradeUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onGoIap: () -> Unit,
    onGoSubscription: () -> Unit,
    onGoSubscriptionTrial: () -> Unit,
    onRestorePurchase: () -> Unit,
    onManageSubscription: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                UpgradeUiState.Loading -> LoadingContent()
                is UpgradeUiState.Unavailable -> UnavailableContent(onRetry = onRetry)
                is UpgradeUiState.Loaded -> {
                    if (state.ownsAnything) {
                        UpgradeOwnershipContent(
                            state = state,
                            onManageSubscription = onManageSubscription,
                            onSwitchToIap = onGoIap,
                            onRestorePurchase = onRestorePurchase,
                        )
                    } else {
                        AcquisitionContent(
                            state = state,
                            onGoIap = onGoIap,
                            onGoSubscription = onGoSubscription,
                            onGoSubscriptionTrial = onGoSubscriptionTrial,
                            onRestorePurchase = onRestorePurchase,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AcquisitionContent(
    state: UpgradeUiState.Loaded,
    onGoIap: () -> Unit,
    onGoSubscription: () -> Unit,
    onGoSubscriptionTrial: () -> Unit,
    onRestorePurchase: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ButlerMascot(
            modifier = Modifier.size(88.dp),
            variant = ButlerMascotMode.Animated.Drink(),
        )
        Spacer(modifier = Modifier.size(8.dp))
        ColoredTitleText(
            fullTitle = stringResource(R.string.app_name_upgraded),
            postfix = stringResource(R.string.app_name_upgrade_postfix),
            style = MaterialTheme.typography.headlineMedium,
        )
    }

    val grace = state.grace
    val youngGrace = grace != null && !grace.showDiagnostics

    if (grace != null) {
        GraceCard(
            grace = grace,
            restoreInProgress = state.restoreInProgress,
            onRestorePurchase = onRestorePurchase,
        )
    }

    // A young grace episode stays calm: no pitch, no offers, no restore banner.
    if (!youngGrace) {
        if (state.wasPreviouslyPro && grace == null) {
            RestoreBanner(onRestorePurchase = onRestorePurchase, restoreInProgress = state.restoreInProgress)
        }
        PreambleCard()
        BenefitsList()
        AcquisitionOffers(
            state = state,
            onGoSubscription = onGoSubscription,
            onGoSubscriptionTrial = onGoSubscriptionTrial,
            onGoIap = onGoIap,
        )
        UpgradeRestoreSection(
            onRestorePurchase = onRestorePurchase,
            restoreInProgress = state.restoreInProgress,
        )
    }
}

@Composable
private fun RestoreBanner(
    onRestorePurchase: () -> Unit,
    restoreInProgress: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.upgrade_screen_restore_banner_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = stringResource(R.string.upgrade_screen_restore_banner_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Button(
                onClick = onRestorePurchase,
                enabled = !restoreInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (restoreInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.upgrade_screen_restore_purchase_action))
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun UnavailableContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.upgrades_gplay_unavailable_error_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.upgrade_screen_unavailable_retry_action))
        }
    }
}

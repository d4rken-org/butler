package eu.darken.butler.upgrade.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
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
    var dialog by remember { mutableStateOf<UpgradeDialog?>(null) }

    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    val restoreSuccessMessage = stringResource(R.string.upgrade_screen_restore_success_message)
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                UpgradeEvents.RestoreFailed -> dialog = UpgradeDialog.RestoreFailed
                UpgradeEvents.RestoreSucceeded ->
                    Toast.makeText(context, restoreSuccessMessage, Toast.LENGTH_LONG).show()
                UpgradeEvents.SubscriptionStillRenewing -> dialog = UpgradeDialog.SubStillRenewing
                UpgradeEvents.SubscriptionCheckFailed -> dialog = UpgradeDialog.SubCheckFailed
            }
        }
    }

    state?.let { current ->
        UpgradeScreen(
            state = current,
            onNavigateUp = { vm.navUp() },
            onIap = { vm.onGoIap(context as Activity) },
            onSubscription = { vm.onGoSubscription(context as Activity) },
            onSubscriptionTrial = { vm.onGoSubscriptionTrial(context as Activity) },
            onRestore = { vm.restorePurchase() },
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
internal fun UpgradeScreen(
    state: UpgradeUiState,
    onNavigateUp: () -> Unit,
    onIap: () -> Unit,
    onSubscription: () -> Unit,
    onSubscriptionTrial: () -> Unit,
    onRestore: () -> Unit,
    onManageSubscription: () -> Unit,
    onRetry: () -> Unit,
) {
    val loaded = state as? UpgradeUiState.Loaded
    // Owners get the ownership presentation: no pitch/benefits/offers box; the one-time purchase
    // appears only as the ownership view's own switch offer, locked while the sub still renews.
    val ownedState = loaded?.takeIf { it.ownsAnything }

    UpgradeScreenScaffold(
        title = {
            // Grace users are Pro too, so they get the upgraded title; a "Get Butler Pro" title on
            // the status screen would contradict the rest of the app.
            if (ownedState != null || loaded?.grace != null) {
                UpgradeTitle()
            } else {
                Text(stringResource(R.string.upgrade_screen_title))
            }
        },
        onNavigateUp = onNavigateUp,
    ) { paddingValues ->
        UpgradeScreenContent(
            paddingValues = paddingValues,
            contentPadding = PaddingValues(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 32.dp),
        ) {
            if (ownedState == null) {
                // Owners get the mascot inside the congrats hero card instead. An aged grace
                // episode swaps in the unimpressed mascot to match its "needs attention" copy.
                UpgradeHeader(
                    mascotSize = 88.dp,
                    happy = loaded?.grace?.showDiagnostics != true,
                )
            }

            if (ownedState != null) {
                UpgradeOwnershipContent(
                    state = ownedState,
                    onIap = onIap,
                    onManageSubscription = onManageSubscription,
                    onRestore = onRestore,
                )
            } else {
                UpgradeAcquisitionContent(
                    state = state,
                    onIap = onIap,
                    onSubscription = onSubscription,
                    onSubscriptionTrial = onSubscriptionTrial,
                    onRestore = onRestore,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun UpgradeAcquisitionContent(
    state: UpgradeUiState,
    onIap: () -> Unit,
    onSubscription: () -> Unit,
    onSubscriptionTrial: () -> Unit,
    onRestore: () -> Unit,
    onRetry: () -> Unit,
) {
    val loaded = state as? UpgradeUiState.Loaded
    val inGrace = loaded?.grace != null

    loaded?.grace?.let { grace ->
        UpgradeGraceCard(
            showDiagnostics = grace.showDiagnostics,
            onRestore = onRestore,
            restoreInProgress = loaded.restoreInProgress,
        )
    }

    // Grace users never see the pitch (Pro already), and the offers follow the episode age: a young
    // episode shows calm status only, an aged one adds restore AND the offers so an actually-expired
    // subscriber can switch without waiting out the full grace window.
    if (!inGrace) {
        UpgradePreambleCard(
            text = stringResource(R.string.upgrade_screen_preamble),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        )

        if (loaded?.wasPreviouslyPro == true) {
            UpgradeRestoreSection(
                title = stringResource(R.string.upgrade_screen_restore_banner_title),
                body = stringResource(R.string.upgrade_screen_restore_banner_body),
                onRestore = onRestore,
                modifier = Modifier.testTag(UpgradeScreenTags.RESTORE_BANNER),
                restoreInProgress = loaded.restoreInProgress,
                emphasized = true,
                restoreTag = UpgradeScreenTags.RESTORE_BANNER_ACTION,
            )
        }

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_benefits_title),
            icon = Icons.TwoTone.AutoAwesome,
        ) {
            UpgradeBenefitsList()
        }
    }

    if (!inGrace || loaded?.grace?.showDiagnostics == true) {
        UpgradeOffersBox(
            state = state,
            onIap = onIap,
            onSubscription = onSubscription,
            onSubscriptionTrial = onSubscriptionTrial,
            onRetry = onRetry,
        )
    }

    // Restore is account reconciliation, not an offer — its own section after the offers, for plain
    // acquisition only (returning buyers get the emphasized section up top; grace owns its own).
    if (loaded != null && !loaded.wasPreviouslyPro && loaded.grace == null) {
        UpgradeRestoreSection(
            title = stringResource(R.string.upgrade_screen_restore_banner_title),
            body = stringResource(R.string.upgrade_screen_restore_body),
            onRestore = onRestore,
            restoreInProgress = loaded.restoreInProgress,
        )
    }
}

@Composable
private fun UpgradeOffersBox(
    state: UpgradeUiState,
    onIap: () -> Unit,
    onSubscription: () -> Unit,
    onSubscriptionTrial: () -> Unit,
    onRetry: () -> Unit,
) {
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "upgrade-offers",
    ) { current ->
        when (current) {
            UpgradeUiState.Loading -> UpgradeActionCard { UpgradeLoadingBlock() }
            is UpgradeUiState.Unavailable -> UpgradeInlineStateCard(
                title = stringResource(R.string.upgrades_gplay_unavailable_error_title),
                body = stringResource(R.string.upgrade_screen_offers_unavailable_message),
                icon = Icons.TwoTone.WarningAmber,
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UpgradeScreenTags.RETRY),
                ) { Text(stringResource(R.string.upgrade_screen_unavailable_retry_action)) }
            }
            is UpgradeUiState.Loaded -> UpgradeActionCard {
                LoadedOffers(
                    state = current,
                    onIap = onIap,
                    onSubscription = onSubscription,
                    onSubscriptionTrial = onSubscriptionTrial,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenAcquisitionPreview() {
    UpgradeScreen(
        state = previewLoaded(),
        onNavigateUp = {}, onIap = {}, onSubscription = {},
        onSubscriptionTrial = {}, onRestore = {}, onManageSubscription = {}, onRetry = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenOwnerSubPreview() {
    UpgradeScreen(
        state = previewLoaded(
            ownership = UpgradeUiState.Ownership(
                subscription = UpgradeUiState.SubscriptionOwnership(isAutoRenewing = true),
            ),
        ),
        onNavigateUp = {}, onIap = {}, onSubscription = {},
        onSubscriptionTrial = {}, onRestore = {}, onManageSubscription = {}, onRetry = {},
    )
}

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
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
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val context = LocalContext.current
    val activity = context as? Activity

    // MainActivity's per-resume refresh only covers the entitlement, never the screen-local SKU
    // query — so a transient Play outage would leave the retry card up until it's tapped by hand.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    // rememberSaveable, not remember: these are driven by one-shot events that are already consumed
    // from the flow, so a rotation while a dialog is up would drop it for good.
    var showRestoreFailed by rememberSaveable { mutableStateOf(false) }
    var showRestoreInconclusive by rememberSaveable { mutableStateOf(false) }
    var showStillRenewing by rememberSaveable { mutableStateOf(false) }
    var showCheckFailed by rememberSaveable { mutableStateOf(false) }

    val restoreSuccessMessage = stringResource(R.string.upgrade_screen_restore_success_message)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                UpgradeEvents.RestoreSucceeded ->
                    Toast.makeText(context, restoreSuccessMessage, Toast.LENGTH_LONG).show()

                UpgradeEvents.RestoreFailed -> showRestoreFailed = true
                UpgradeEvents.RestoreInconclusive -> showRestoreInconclusive = true
                UpgradeEvents.SubscriptionStillRenewing -> showStillRenewing = true
                UpgradeEvents.SubscriptionCheckFailed -> showCheckFailed = true
            }
        }
    }

    if (showRestoreFailed) {
        RestoreFailedDialog(
            onContactSupport = {
                showRestoreFailed = false
                vm.onContactSupport()
            },
            onDismiss = { showRestoreFailed = false },
        )
    }

    if (showRestoreInconclusive) {
        RestoreInconclusiveDialog(
            onRetry = {
                showRestoreInconclusive = false
                vm.restorePurchase()
            },
            onDismiss = { showRestoreInconclusive = false },
        )
    }

    if (showStillRenewing) {
        SimpleMessageDialog(
            title = stringResource(R.string.upgrade_screen_sub_still_renewing_title),
            message = stringResource(R.string.upgrade_screen_sub_still_renewing_message),
            onDismiss = { showStillRenewing = false },
            positiveLabel = stringResource(R.string.upgrade_screen_manage_subscription_action),
            onPositive = {
                showStillRenewing = false
                vm.onManageSubscription()
            },
        )
    }

    if (showCheckFailed) {
        SimpleMessageDialog(
            title = stringResource(R.string.upgrade_screen_sub_check_failed_title),
            message = stringResource(R.string.upgrade_screen_sub_check_failed_message),
            onDismiss = { showCheckFailed = false },
        )
    }

    val uiState by vm.state.collectAsStateWithLifecycle()

    UpgradeScreen(
        uiState = uiState,
        onIap = { activity?.let { vm.onGoIap(it) } },
        onSubscription = { activity?.let { vm.onGoSubscription(it) } },
        onSubscriptionTrial = { activity?.let { vm.onGoSubscriptionTrial(it) } },
        onRestore = vm::restorePurchase,
        onManageSubscription = vm::onManageSubscription,
        onRetry = vm::retrySkuQuery,
        onNavigateUp = vm::navUp,
    )
}

@Composable
internal fun UpgradeScreen(
    uiState: UpgradeUiState = UpgradeUiState.Loading,
    onIap: () -> Unit = {},
    onSubscription: () -> Unit = {},
    onSubscriptionTrial: () -> Unit = {},
    onRestore: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
    onRetry: () -> Unit = {},
    onNavigateUp: () -> Unit = {},
) {
    // Owners get the ownership presentation: no acquisition upsell (pitch, benefits, offers box)
    // anywhere — the one-time purchase appears only as the ownership view's own switch offer,
    // locked while the subscription still renews.
    val loaded = uiState as? UpgradeUiState.Loaded
    val ownedState = loaded?.takeIf { it.ownership.ownsAnything }

    UpgradeScreenScaffold(
        title = {
            // Grace users are still Pro: they get the status title too — a "Upgrade Butler" title
            // on the status screen would contradict the rest of the app, which behaves upgraded.
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
                // Owners get the mascot inside the congrats hero card instead. Once a grace
                // episode ages into the diagnostics stage, the mascot joins the mood: unimpressed
                // at Google Play, matching the setup card's "needs your attention" face. The young
                // episode keeps the happy face — its message is that nothing is wrong.
                UpgradeHeader(
                    mascotSize = 88.dp,
                    happy = loaded?.grace?.showDiagnostics != true,
                )
            }

            if (ownedState != null) {
                UpgradeOwnershipContent(
                    uiState = ownedState,
                    onIap = onIap,
                    onManageSubscription = onManageSubscription,
                    onRestore = onRestore,
                )
            } else {
                UpgradeAcquisitionContent(
                    uiState = uiState,
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
    uiState: UpgradeUiState,
    onIap: () -> Unit,
    onSubscription: () -> Unit,
    onSubscriptionTrial: () -> Unit,
    onRestore: () -> Unit,
    onRetry: () -> Unit,
) {
    val loadedState = uiState as? UpgradeUiState.Loaded
    val inGrace = loadedState?.grace != null
    loadedState?.grace?.let { grace ->
        UpgradeGraceCard(
            showDiagnostics = grace.showDiagnostics,
            onRestore = onRestore,
            busy = loadedState.busy,
        )
    }

    // Grace users never see the pitch (they are Pro, sales copy next to a "still active" card
    // reads as a contradiction), and the OFFERS follow the episode age — the client can't tell a
    // blip from a lapsed purchase, so time is the arbiter: a young episode (likely self-healing
    // blip) shows calm status only, an aged one (likely really gone) adds restore AND the offers,
    // so an expired subscriber can switch without waiting out the full grace window.
    if (!inGrace) {
        UpgradePreambleCard(
            text = stringResource(R.string.upgrade_screen_preamble),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        )

        if (loadedState != null && loadedState.wasPreviouslyPro) {
            // The targeted returning-buyer nudge: prominent placement and emphasis, and the ONLY
            // restore affordance on the screen — a second one below would make the screen feel
            // uncertain about its own advice.
            UpgradeRestoreSection(
                title = stringResource(R.string.upgrade_screen_restore_banner_title),
                body = stringResource(R.string.upgrade_screen_restore_banner_body),
                onRestore = onRestore,
                modifier = Modifier.testTag(UpgradeScreenTags.RESTORE_BANNER),
                busy = loadedState.busy,
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

    // During a YOUNG grace episode the offers box is hidden: likely a blip, and offers next to
    // "Pro is still active" would contradict it. An aged episode brings them back.
    if (!inGrace || loadedState?.grace?.showDiagnostics == true) {
        UpgradeOffersBox(
            uiState = uiState,
            onIap = onIap,
            onSubscription = onSubscription,
            onSubscriptionTrial = onSubscriptionTrial,
            onRetry = onRetry,
        )
    }

    // Restore is account reconciliation, not an offer — its own described section, after the
    // offers. Only for plain acquisition: returning buyers get the emphasized section up top
    // instead, and grace users' restore is owned by the grace card's two-stage disclosure.
    if (loadedState != null && !loadedState.wasPreviouslyPro && loadedState.grace == null) {
        UpgradeRestoreSection(
            title = stringResource(R.string.upgrade_screen_restore_banner_title),
            body = stringResource(R.string.upgrade_screen_restore_body),
            onRestore = onRestore,
            busy = loadedState.busy,
        )
    }
}

// All purchase framing lives inside the offers box (LoadedOffers) — no separate explainer card.
// Each state brings its OWN container: the error state is a full card itself, wrapping it in the
// action card produced a card-in-card.
@Composable
private fun UpgradeOffersBox(
    uiState: UpgradeUiState,
    onIap: () -> Unit,
    onSubscription: () -> Unit,
    onSubscriptionTrial: () -> Unit,
    onRetry: () -> Unit,
) {
    AnimatedContent(
        targetState = uiState,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "upgrade-offers",
    ) { state ->
        when (state) {
            UpgradeUiState.Loading -> UpgradeActionCard { UpgradeLoadingBlock() }
            is UpgradeUiState.Unavailable -> UpgradeInlineStateCard(
                title = stringResource(R.string.upgrade_screen_offers_unavailable_title),
                body = stringResource(R.string.upgrade_screen_offers_unavailable_message),
                icon = Icons.TwoTone.WarningAmber,
            ) {
                // Play can be slow rather than broken (cold store, first sign-in): let
                // the user re-run the offer queries instead of leaving a dead screen.
                // No reset needed: this composable unmounts the moment the state leaves Unavailable.
                var retryTapped by remember { mutableStateOf(false) }
                OutlinedButton(
                    // Guard inside the callback, not just via `enabled`: `enabled` only takes effect
                    // after recomposition, so two taps in the same frame would both fire.
                    onClick = {
                        if (!retryTapped) {
                            retryTapped = true
                            onRetry()
                        }
                    },
                    enabled = !retryTapped,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UpgradeScreenTags.RETRY),
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_retry_action))
                }
            }

            is UpgradeUiState.Loaded -> UpgradeActionCard {
                LoadedOffers(
                    uiState = state,
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
private fun UpgradeScreenLoadingPreview() {
    UpgradeScreen(uiState = UpgradeUiState.Loading)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenLoadedPreview() {
    UpgradeScreen(uiState = previewLoaded())
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenReturningBuyerPreview() {
    UpgradeScreen(
        uiState = previewLoaded(
            subscriptionAction = SubscriptionAction.STANDARD,
            wasPreviouslyPro = true,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenOwnerSubPreview() {
    UpgradeScreen(
        uiState = previewLoaded(
            ownership = Ownership(subscription = SubscriptionOwnership(isAutoRenewing = true)),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenUnavailablePreview() {
    UpgradeScreen(
        uiState = UpgradeUiState.Unavailable(
            error = RuntimeException("Google Play unavailable"),
        ),
    )
}

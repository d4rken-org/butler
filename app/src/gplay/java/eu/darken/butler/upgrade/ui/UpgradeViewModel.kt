package eu.darken.butler.upgrade.ui

import android.app.Activity
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.BuildConfig
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.core.OurSku
import eu.darken.butler.upgrade.core.UpgradeRepoGplay
import eu.darken.butler.upgrade.core.UpgradeRepoGplay.Companion.GRACE_DIAGNOSTICS_AFTER_MS
import eu.darken.butler.upgrade.core.billing.Sku
import eu.darken.butler.upgrade.core.billing.SkuDetails
import eu.darken.butler.upgrade.ui.UpgradeUiState.Companion.toOwnership
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull

private sealed interface QueryState {
    data object Loading : QueryState
    data class Loaded(val data: SkuData?) : QueryState
}

private data class SkuData(
    val iap: Collection<SkuDetails>?,
    val sub: Collection<SkuDetails>?,
)

// Single atomic operation state: one compareAndSet acquires it, so a restore and a switch-verify
// started concurrently on the multi-threaded Default dispatcher can never both run a Play operation.
private enum class Op { Idle, Restoring, Verifying }

@HiltViewModel(assistedFactory = UpgradeViewModel.Factory::class)
class UpgradeViewModel @AssistedInject constructor(
    @Assisted private val manage: Boolean,
    dispatcherProvider: DispatcherProvider,
    private val upgradeRepo: UpgradeRepoGplay,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatcherProvider, logTag("Upgrade", "Screen", "VM")) {

    val events = SingleEventFlow<UpgradeEvents>()

    private val operation = MutableStateFlow(Op.Idle)
    private val skuRetry = MutableStateFlow(0)

    init {
        // Only the acquisition route auto-closes when the user becomes Pro; the manage/status route
        // stays open so the user can read their ownership and switch subscription -> one-time.
        if (!manage) {
            upgradeRepo.upgradeInfo
                .filter { it.isUpgraded }
                .take(1)
                .onEach { navUp() }
                .launchInViewModel()
        }
    }

    private val skuQuery = skuRetry.flatMapLatest {
        flow {
            emit(QueryState.Loading)
            val data = withTimeoutOrNull(SKU_QUERY_TIMEOUT_MS) {
                coroutineScope {
                    val iap = async { safeQuery { upgradeRepo.querySkus(OurSku.Iap.PRO_UPGRADE) } }
                    val sub = async { safeQuery { upgradeRepo.querySkus(OurSku.Sub.PRO_UPGRADE) } }
                    SkuData(iap = iap.await(), sub = sub.await())
                }
            }
            emit(QueryState.Loaded(data))
        }
    }

    val state = combine(
        skuQuery,
        upgradeRepo.upgradeInfo,
        upgradeRepo.isSettled,
        upgradeRepo.wasEverPro,
        upgradeRepo.proUnconfirmedSince,
        upgradeRepo.graceTick,
        operation,
    ) { skuState, info, settled, wasEverPro, unconfirmedSince, _, op ->
        val ownership = info.toOwnership()
        val ownsAnything = ownership.ownsAnything

        // Grace = Pro purely via the local grace window (not actually owning anything).
        val grace = if (info.gracePeriod && !ownsAnything) {
            val aged = unconfirmedSince > 0L &&
                (System.currentTimeMillis() - unconfirmedSince) >= GRACE_DIAGNOSTICS_AFTER_MS
            UpgradeUiState.GraceHint(showDiagnostics = aged)
        } else {
            null
        }

        // Owners and grace render price-independently: a failed price query must never blank their
        // screen into Loading/Unavailable.
        val priceIndependent = ownsAnything || grace != null
        val skuData = (skuState as? QueryState.Loaded)?.data
        // Both prices missing: either the query timed out (skuData == null) or both product-type
        // queries came back empty/failed.
        val bothPricesMissing = skuData == null || (skuData.iap == null && skuData.sub == null)

        when {
            skuState is QueryState.Loading && !priceIndependent -> UpgradeUiState.Loading
            skuState is QueryState.Loaded && bothPricesMissing && !priceIndependent ->
                UpgradeUiState.Unavailable(error = null)
            else -> {
                val iapOffer = skuData?.iap?.firstOrNull()?.details?.oneTimePurchaseOfferDetails
                val subOffer = skuData?.sub?.firstOrNull()?.details?.subscriptionOfferDetails
                    ?.singleOrNull { OurSku.Sub.PRO_UPGRADE.BASE_OFFER.matches(it) }
                val trialAvailable = skuData?.sub?.firstOrNull()?.details?.subscriptionOfferDetails
                    ?.any { OurSku.Sub.PRO_UPGRADE.TRIAL_OFFER.matches(it) } == true
                val subPrice = subOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice

                val subAction = when {
                    subOffer == null -> UpgradeUiState.SubscriptionAction.UNAVAILABLE
                    trialAvailable -> UpgradeUiState.SubscriptionAction.TRIAL
                    else -> UpgradeUiState.SubscriptionAction.STANDARD
                }

                UpgradeUiState.Loaded(
                    manage = manage,
                    settled = settled,
                    ownership = ownership,
                    grace = grace,
                    subscriptionAction = subAction,
                    subscriptionPrice = subPrice,
                    trialPrice = subPrice,
                    iapPrice = iapOffer?.formattedPrice,
                    wasPreviouslyPro = wasEverPro && !info.isUpgraded,
                    restoreInProgress = op == Op.Restoring,
                    verificationInProgress = op == Op.Verifying,
                )
            }
        }
    }.asStateFlow()

    fun retrySkuQuery() {
        log(tag) { "retrySkuQuery()" }
        skuRetry.value += 1
    }

    fun onGoIap(activity: Activity) = launch {
        // Single-flight; also excludes a concurrent restore.
        if (!operation.compareAndSet(Op.Idle, Op.Verifying)) {
            log(tag) { "onGoIap ignored, operation in progress" }
            return@launch
        }
        log(tag) { "onGoIap($activity)" }
        try {
            // Fail-closed sub->IAP gate: authoritatively check for a still-renewing/pending sub first.
            val status = safeVerify { upgradeRepo.queryCurrentSubscriptions() }
            when {
                status == null -> events.tryEmit(UpgradeEvents.SubscriptionCheckFailed)
                status.hasBlockingSubscription -> events.tryEmit(UpgradeEvents.SubscriptionStillRenewing)
                else -> upgradeRepo.launchBillingFlow(activity, OurSku.Iap.PRO_UPGRADE, null) {
                    errorEvents.tryEmit(it)
                }
            }
        } finally {
            operation.value = Op.Idle
        }
    }

    fun onGoSubscription(activity: Activity) = launchSubscribe(activity, OurSku.Sub.PRO_UPGRADE.BASE_OFFER)

    fun onGoSubscriptionTrial(activity: Activity) = launchSubscribe(activity, OurSku.Sub.PRO_UPGRADE.TRIAL_OFFER)

    private fun launchSubscribe(activity: Activity, offer: Sku.Subscription.Offer) = launch {
        if (!operation.compareAndSet(Op.Idle, Op.Verifying)) {
            log(tag) { "launchSubscribe ignored, operation in progress" }
            return@launch
        }
        log(tag) { "launchSubscribe($activity, $offer)" }
        try {
            // Symmetric fail-closed gate: an IAP owner whose INAPP query failed must not buy a sub too.
            val iapOwned = safeVerify { upgradeRepo.isIapOwnedNow() }
            when {
                iapOwned == null -> events.tryEmit(UpgradeEvents.SubscriptionCheckFailed)
                // Already Pro via the one-time purchase: the fresh refresh heals upgradeInfo and the
                // screen switches to the ownership view; nothing to launch.
                iapOwned == true -> log(tag, INFO) { "IAP already owned -> not launching subscription" }
                else -> upgradeRepo.launchBillingFlow(activity, OurSku.Sub.PRO_UPGRADE, offer) {
                    errorEvents.tryEmit(it)
                }
            }
        } finally {
            operation.value = Op.Idle
        }
    }

    fun restorePurchase() = launch {
        if (!operation.compareAndSet(Op.Idle, Op.Restoring)) {
            log(tag) { "restorePurchase ignored, operation in progress" }
            return@launch
        }
        log(tag) { "restorePurchase()" }
        try {
            coroutineScope {
                // Pad the spinner to a minimum visible duration concurrently with the real query so a
                // sub-second check still reads as "we checked" rather than a one-frame flash.
                val pad = async { delay(RESTORE_MIN_VISIBLE_MS) }
                val restored = try {
                    withTimeoutOrNull(RESTORE_TIMEOUT_MS) { upgradeRepo.restorePurchaseNow() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(tag, WARN) { "Restore errored: ${e.asLog()}" }
                    errorEvents.tryEmit(e)
                    pad.await()
                    return@coroutineScope
                }
                pad.await()
                when {
                    restored == null -> {
                        log(tag, WARN) { "Restore timed out" }
                        events.tryEmit(UpgradeEvents.RestoreFailed)
                    }
                    // Success requires ACTUAL owned purchases; a grace-only result is not a restore.
                    restored.upgrades.isNotEmpty() -> {
                        log(tag, INFO) { "Restore succeeded" }
                        events.tryEmit(UpgradeEvents.RestoreSucceeded)
                    }
                    else -> {
                        log(tag, WARN) { "Restore found nothing" }
                        events.tryEmit(UpgradeEvents.RestoreFailed)
                    }
                }
            }
        } finally {
            operation.value = Op.Idle
        }
    }

    fun onManageSubscription() {
        log(tag) { "onManageSubscription()" }
        webpageTool.open(
            "https://play.google.com/store/account/subscriptions" +
                "?sku=${OurSku.Sub.PRO_UPGRADE.id}&package=${BuildConfig.APPLICATION_ID}"
        )
    }

    private suspend fun <T> safeQuery(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(tag, WARN) { "Query failed: ${e.asLog()}" }
        null
    }

    private suspend fun <T> safeVerify(block: suspend () -> T): T? = try {
        withTimeoutOrNull(VERIFY_TIMEOUT_MS) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(tag, WARN) { "Verify failed: ${e.asLog()}" }
        null
    }

    @AssistedFactory
    interface Factory {
        fun create(manage: Boolean): UpgradeViewModel
    }

    companion object {
        private const val SKU_QUERY_TIMEOUT_MS = 15_000L
        private const val VERIFY_TIMEOUT_MS = 10_000L
        private const val RESTORE_TIMEOUT_MS = 15_000L
        private const val RESTORE_MIN_VISIBLE_MS = 1_500L
    }
}

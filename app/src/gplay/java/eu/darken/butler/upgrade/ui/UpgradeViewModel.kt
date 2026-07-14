package eu.darken.butler.upgrade.ui

import android.app.Activity
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.core.OurSku
import eu.darken.butler.upgrade.core.UpgradeRepoGplay
import eu.darken.butler.upgrade.core.billing.GplayServiceUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private sealed interface QueryState<out T> {
    data object Loading : QueryState<Nothing>
    data class Loaded<T>(val data: T?) : QueryState<T>
}

@HiltViewModel
class UpgradeViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val upgradeRepo: UpgradeRepoGplay,
) : ViewModel4(dispatcherProvider, logTag("Upgrade", "Screen", "VM")) {

    val events = SingleEventFlow<UpgradeEvents>()

    private val restoring = MutableStateFlow(false)
    private var priceErrorReported = false

    init {
        upgradeRepo.upgradeInfo
            .filter { it.isUpgraded }
            .take(1)
            .onEach { navUp() }
            .launchInViewModel()
    }

    val state = combine(
        flow {
            emit(QueryState.Loading)
            val data = withTimeoutOrNull(5000) {
                try {
                    upgradeRepo.querySkus(OurSku.Iap.PRO_UPGRADE)
                } catch (e: Exception) {
                    errorEvents.emit(e)
                    null
                }
            }
            emit(QueryState.Loaded(data))
        },
        flow {
            emit(QueryState.Loading)
            val data = withTimeoutOrNull(5000) {
                try {
                    upgradeRepo.querySkus(OurSku.Sub.PRO_UPGRADE)
                } catch (e: Exception) {
                    errorEvents.emit(e)
                    null
                }
            }
            emit(QueryState.Loaded(data))
        },
        upgradeRepo.upgradeInfo,
        upgradeRepo.wasEverPro,
        restoring,
    ) { iapQueryState, subQueryState, current, wasEverPro, isRestoring ->
        val isLoadingPrices = iapQueryState is QueryState.Loading || subQueryState is QueryState.Loading

        val iap = (iapQueryState as? QueryState.Loaded)?.data
        val sub = (subQueryState as? QueryState.Loaded)?.data

        // Emit once per ViewModel: the combine transform re-runs for every restoring/wasEverPro
        // change, which must not re-pop the "Play unavailable" dialog for the same failed queries.
        if (!isLoadingPrices && iap == null && sub == null && !priceErrorReported) {
            priceErrorReported = true
            errorEvents.emit(
                GplayServiceUnavailableException(RuntimeException("IAP and SUB data request timed out."))
            )
        }

        val iapOffer = iap?.firstOrNull()?.details?.oneTimePurchaseOfferDetails
        val iapState = State.Iap(
            available = iapOffer != null && current.upgrades.none { it.sku == OurSku.Iap.PRO_UPGRADE },
            formattedPrice = iapOffer?.formattedPrice,
        )

        // Play can withhold offers (e.g. trial eligibility): log what actually came back so
        // "Play withheld the trial" vs "offer matching failed" is diagnosable from logs.
        sub?.firstOrNull()?.details?.subscriptionOfferDetails?.let { offers ->
            log(tag) { "Subscription offers returned: ${offers.map { "${it.basePlanId}/${it.offerId}" }}" }
        }

        val subOffer = sub?.firstOrNull()?.details?.subscriptionOfferDetails?.singleOrNull { offer ->
            OurSku.Sub.PRO_UPGRADE.BASE_OFFER.matches(offer)
        }
        val subState = State.Sub(
            available = subOffer != null && current.upgrades.none { it.sku == OurSku.Sub.PRO_UPGRADE },
            formattedPrice = subOffer?.let { it.pricingPhases.pricingPhaseList.firstOrNull()?.formattedPrice },
        )

        val trialOffer = sub?.firstOrNull()?.details?.subscriptionOfferDetails?.any { offer ->
            OurSku.Sub.PRO_UPGRADE.TRIAL_OFFER.matches(offer)
        }
        val trialState = State.Trial(
            // `any` returns false when Play withheld the trial offer - `!= null` was true for any
            // non-empty offer list, showing a trial button whose purchase flow couldn't find its offer.
            available = trialOffer == true,
            formattedPrice = subState.formattedPrice,
        )

        State(
            isLoadingPrices = isLoadingPrices,
            iapState = iapState,
            subState = subState,
            trialState = trialState,
            // Hidden while a grace period or an actual purchase keeps the user Pro.
            wasPreviouslyPro = wasEverPro && !current.isUpgraded,
            restoreInProgress = isRestoring,
        )
    }.asStateFlow()

    data class State(
        val isLoadingPrices: Boolean,
        val iapState: Iap,
        val subState: Sub,
        val trialState: Trial,
        val wasPreviouslyPro: Boolean = false,
        val restoreInProgress: Boolean = false,
    ) {

        class Iap(
            val available: Boolean,
            val formattedPrice: String?,
        )

        data class Sub(
            val available: Boolean,
            val formattedPrice: String?,
        )

        data class Trial(
            val available: Boolean,
            val formattedPrice: String?,
        )
    }

    fun onGoIap(activity: Activity) {
        log(tag) { "onGoIap($activity)" }
        upgradeRepo.launchBillingFlow(activity, OurSku.Iap.PRO_UPGRADE, null) { errorEvents.tryEmit(it) }
    }

    fun onGoSubscription(activity: Activity) {
        log(tag) { "onGoSubscription($activity)" }
        upgradeRepo.launchBillingFlow(activity, OurSku.Sub.PRO_UPGRADE, OurSku.Sub.PRO_UPGRADE.BASE_OFFER) {
            errorEvents.tryEmit(it)
        }
    }

    fun onGoSubscriptionTrial(activity: Activity) {
        log(tag) { "onGoSubscriptionTrial($activity)" }
        upgradeRepo.launchBillingFlow(activity, OurSku.Sub.PRO_UPGRADE, OurSku.Sub.PRO_UPGRADE.TRIAL_OFFER) {
            errorEvents.tryEmit(it)
        }
    }

    fun restorePurchase() = launch {
        // Single-flight: repeated taps while a restore is running (worst case bounded by
        // RESTORE_TIMEOUT_MS) must not stack concurrent restores and duplicate result dialogs.
        if (!restoring.compareAndSet(expect = false, update = true)) {
            log(tag) { "restorePurchase() ignored, already in progress" }
            return@launch
        }
        log(tag) { "restorePurchase()" }

        try {
            val restored = withTimeoutOrNull(RESTORE_TIMEOUT_MS) { upgradeRepo.restorePurchaseNow() }
            when {
                restored == null -> {
                    // Play never answered in time; the restore-failed dialog already suggests waiting /
                    // clearing the Play cache, which fits a timeout too.
                    log(tag, WARN) { "Restore purchase timed out" }
                    events.tryEmit(UpgradeEvents.RestoreFailed)
                }

                restored.isUpgraded -> log(tag, INFO) { "Restored purchase :))" }

                else -> {
                    log(tag, WARN) { "Restore purchase failed" }
                    events.tryEmit(UpgradeEvents.RestoreFailed)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Play/billing error (e.g. service unavailable): surface the proper error dialog instead
            // of the generic "restore failed" message, so the user can tell the two cases apart.
            log(tag, WARN) { "Restore purchase errored: ${e.asLog()}" }
            errorEvents.tryEmit(e)
        } finally {
            // Reset only after result handling, so the single-flight guard covers the whole action.
            restoring.value = false
        }
    }

    companion object {
        private const val RESTORE_TIMEOUT_MS = 15_000L
    }
}
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
    ) { iapQueryState, subQueryState, current, wasEverPro ->
        val isLoadingPrices = iapQueryState is QueryState.Loading || subQueryState is QueryState.Loading

        val iap = (iapQueryState as? QueryState.Loaded)?.data
        val sub = (subQueryState as? QueryState.Loaded)?.data

        if (!isLoadingPrices && iap == null && sub == null) {
            errorEvents.emit(
                GplayServiceUnavailableException(RuntimeException("IAP and SUB data request timed out."))
            )
        }

        val iapOffer = iap?.firstOrNull()?.details?.oneTimePurchaseOfferDetails
        val iapState = State.Iap(
            available = iapOffer != null && current.upgrades.none { it.sku == OurSku.Iap.PRO_UPGRADE },
            formattedPrice = iapOffer?.formattedPrice,
        )

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
            available = trialOffer != null,
            formattedPrice = subState.formattedPrice,
        )

        State(
            isLoadingPrices = isLoadingPrices,
            iapState = iapState,
            subState = subState,
            trialState = trialState,
            // Hidden while a grace period or an actual purchase keeps the user Pro.
            wasPreviouslyPro = wasEverPro && !current.isUpgraded,
        )
    }.asStateFlow()

    data class State(
        val isLoadingPrices: Boolean,
        val iapState: Iap,
        val subState: Sub,
        val trialState: Trial,
        val wasPreviouslyPro: Boolean = false,
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
        upgradeRepo.launchBillingFlow(activity, OurSku.Iap.PRO_UPGRADE, null)
    }

    fun onGoSubscription(activity: Activity) {
        log(tag) { "onGoSubscription($activity)" }
        upgradeRepo.launchBillingFlow(activity, OurSku.Sub.PRO_UPGRADE, OurSku.Sub.PRO_UPGRADE.BASE_OFFER)
    }

    fun onGoSubscriptionTrial(activity: Activity) {
        log(tag) { "onGoSubscriptionTrial($activity)" }
        upgradeRepo.launchBillingFlow(activity, OurSku.Sub.PRO_UPGRADE, OurSku.Sub.PRO_UPGRADE.TRIAL_OFFER)
    }

    fun restorePurchase() = launch {
        log(tag) { "restorePurchase()" }

        val restored = try {
            withTimeoutOrNull(RESTORE_TIMEOUT_MS) { upgradeRepo.restorePurchaseNow() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Play/billing error (e.g. service unavailable): surface the proper error dialog instead
            // of the generic "restore failed" message, so the user can tell the two cases apart.
            log(tag, WARN) { "Restore purchase errored: ${e.asLog()}" }
            errorEvents.tryEmit(e)
            return@launch
        }

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
    }

    companion object {
        private const val RESTORE_TIMEOUT_MS = 15_000L
    }
}
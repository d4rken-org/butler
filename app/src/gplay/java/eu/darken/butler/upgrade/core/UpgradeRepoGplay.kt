package eu.darken.butler.upgrade.core

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.Purchase
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.core.billing.BillingData
import eu.darken.butler.upgrade.core.billing.BillingManager
import eu.darken.butler.upgrade.core.billing.ItemAlreadyOwnedBillingException
import eu.darken.butler.upgrade.core.billing.PurchasedSku
import eu.darken.butler.upgrade.core.billing.Sku
import eu.darken.butler.upgrade.core.billing.SkuDetails
import eu.darken.butler.upgrade.core.billing.UserCanceledBillingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Singleton
class UpgradeRepoGplay @Inject constructor(
    @AppScope private val scope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val billingManager: BillingManager,
    private val billingCache: BillingCache,
) : UpgradeRepo {

    override val mainWebsite: String = SITE

    init {
        // Fresh-provenance grace stamping: billingData emissions are only produced by fresh query
        // writes or purchase events; replay can't reach this collector (subscribed before the first
        // emission, never re-subscribes). A confirmed known Pro SKU stamps + closes any open episode.
        billingManager.billingData
            .distinctUntilChanged()
            .onEach {
                try {
                    recordProState(Info(billingData = it))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, WARN) { "Failed to record pro state: ${e.asLog()}" }
                }
            }
            .setupCommonEventHandlers(TAG) { "proStateRecorder" }
            .launchIn(scope)

        // Async variant of the launch-result ITEM_ALREADY_OWNED case: reconcile silently.
        billingManager.purchaseFailures
            .filter { it.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED }
            .onEach {
                log(TAG, INFO) { "Async already-owned event -> restoring purchase" }
                try {
                    withTimeoutOrNull(RESTORE_ON_OWNED_TIMEOUT_MS) { restorePurchaseNow() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, WARN) { "Async already-owned restore failed: ${e.asLog()}" }
                }
            }
            .setupCommonEventHandlers(TAG) { "asyncAlreadyOwned" }
            .launchIn(scope)

        // A billing connection we can't (re)establish while the user was recently Pro is exactly the
        // "Play won't confirm" situation the grace diagnostics cover: advance the episode clock.
        billingManager.connectionFailures
            .onEach { failedAt -> openGraceEpisodeIfWarranted(failedAt) }
            .setupCommonEventHandlers(TAG) { "connFailureEpisode" }
            .launchIn(scope)
    }

    // Re-fires evaluation at the grace-window deadline so a continuously open process actually drops
    // Pro past 7/30 days instead of staying Pro merely because no new billing emission arrived.
    private val proDropTick: Flow<Unit> = billingCache.lastProStateAt.flow
        .flatMapLatest { lastAt ->
            flow {
                emit(Unit)
                if (lastAt > 0L) {
                    val remaining = graceWindowMs() - (System.currentTimeMillis() - lastAt)
                    if (remaining > 0L) {
                        delay(remaining + 1_000L)
                        emit(Unit)
                    }
                }
            }
        }

    override val upgradeInfo: Flow<Info> = combine(
        billingManager.billingData
            .map<BillingData, BillingData?> { it }
            .onStart { emit(null) },
        proDropTick,
    ) { data: BillingData?, _ -> data }
        .setupCommonEventHandlers(TAG) { "upgradeInfo1" }
        .map { data: BillingData? -> data.toUpgradeInfo() }
        .distinctUntilChanged()
        .retryWhen { error, attempt ->
            if (error is CancellationException) return@retryWhen false
            // Wrap the DataStore probe: the same store may have caused the failure, and a second
            // failing read must not terminate this process-lifetime flow.
            val graceActive = try {
                val now = System.currentTimeMillis()
                val lastProStateAt = billingCache.lastProStateAt.value()
                (now - lastProStateAt) < graceWindowMs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "Grace probe failed during retry: ${e.asLog()}" }
                false
            }
            log(TAG) { "upgradeInfo retry: graceActive=$graceActive, attempt=$attempt, error=$error" }
            if (graceActive) {
                openGraceEpisodeIfWarranted(System.currentTimeMillis())
                emit(Info(gracePeriod = true, billingData = null))
            } else {
                emit(Info(billingData = null))
            }
            delay((30_000L * 2.0.pow(attempt.toDouble())).toLong().coerceAtMost(RETRY_DELAY_CAP_MS))
            true
        }
        .setupCommonEventHandlers(TAG) { "upgradeInfo2" }
        .shareIn(scope, SharingStarted.WhileSubscribed(3000L, 0L), replay = 1)

    // True once we've ever confirmed a (known) Pro purchase on this install; drives the proactive
    // restore banner. Local signal only — a fresh install or switched Google account starts false.
    val wasEverPro: Flow<Boolean> = billingCache.lastProStateAt.flow
        .map { it > 0 }
        .distinctUntilChanged()

    // False until the first billing reconciliation lands (so acquisition buttons stay disabled while
    // ownership is unknown — closing the fresh-install double-buy window).
    val isSettled: Flow<Boolean> = billingManager.billingData
        .map { true }
        .onStart { emit(false) }
        .distinctUntilChanged()

    // Start of the current unconfirmed episode (0 = none). The diagnostics stage is gated on this.
    val proUnconfirmedSince: Flow<Long> = billingCache.proUnconfirmedSince.flow
        .distinctUntilChanged()

    // Emits now and again at the 24h diagnostics boundary while an episode is open, so a screen that
    // is already open transitions from the quiet stage to the diagnostics stage without a new event.
    val graceTick: Flow<Unit> = billingCache.proUnconfirmedSince.flow
        .flatMapLatest { since ->
            flow {
                emit(Unit)
                if (since > 0L) {
                    val remaining = GRACE_DIAGNOSTICS_AFTER_MS - (System.currentTimeMillis() - since)
                    if (remaining > 0L) {
                        delay(remaining + 1_000L)
                        emit(Unit)
                    }
                }
            }
        }

    // Launches the Play purchase sheet. This does NOT itself enforce a double-billing gate — callers
    // MUST run the fail-closed ownership check first (queryCurrentSubscriptions() before the IAP,
    // isIapOwnedNow() before the subscription). The only caller, UpgradeViewModel, does exactly that.
    fun launchBillingFlow(
        activity: Activity,
        sku: Sku,
        offer: Sku.Subscription.Offer?,
        onError: (Throwable) -> Unit,
    ) {
        log(TAG) { "launchBillingFlow($activity,$sku)" }
        scope.launch {
            try {
                billingManager.startIapFlow(activity, sku, offer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (e) {
                    is UserCanceledBillingException -> log(TAG) { "User canceled billing flow" }

                    is ItemAlreadyOwnedBillingException -> {
                        log(TAG, INFO) { "Launch says already owned -> restoring purchase" }
                        val restored = try {
                            withTimeoutOrNull(RESTORE_ON_OWNED_TIMEOUT_MS) { restorePurchaseNow() }
                        } catch (re: CancellationException) {
                            throw re
                        } catch (re: Exception) {
                            log(TAG, WARN) { "Restore after already-owned failed: ${re.asLog()}" }
                            null
                        }
                        // Only count the reconcile if the SKU we tried to launch actually came back.
                        if (restored?.upgrades?.any { it.sku.id == sku.id } != true) {
                            onError(e)
                        }
                    }

                    else -> {
                        log(TAG) { "startIapFlow failed:${e.asLog()}" }
                        onError(e)
                    }
                }
            }
        }
    }

    suspend fun querySkus(vararg skus: Sku): Collection<SkuDetails> = billingManager.querySkus(*skus)

    // Fresh authoritative SUBS read for the sub->IAP switch gate. Fail-closed: errors propagate so
    // the caller blocks the purchase instead of silently allowing a double charge.
    suspend fun queryCurrentSubscriptions(): SubscriptionStatus {
        log(TAG) { "queryCurrentSubscriptions()" }
        val subs = billingManager.querySubscriptions()
        val ours = subs.filter { p -> p.products.any { it == OurSku.Sub.PRO_UPGRADE.id } }
        // A still-renewing OR pending subscription blocks buying the one-time IAP.
        val blocking = ours.any { it.isAutoRenewing || it.purchaseState == Purchase.PurchaseState.PENDING }
        log(TAG) { "queryCurrentSubscriptions(): ours=$ours, blocking=$blocking" }
        return SubscriptionStatus(hasBlockingSubscription = blocking)
    }

    // Symmetric fresh authoritative INAPP check for the acquisition subscribe gate: an IAP owner whose
    // INAPP query failed must not be able to buy the subscription on top. Uses the INAPP-only query
    // whose error PROPAGATES (fail-closed) — NOT refresh(), which suppresses an INAPP failure whenever
    // the SUBS query returns something and would wrongly read as "IAP not owned".
    suspend fun isIapOwnedNow(): Boolean {
        log(TAG) { "isIapOwnedNow()" }
        val iaps = billingManager.queryInApps()
        return iaps.any { p -> p.products.any { it == OurSku.Iap.PRO_UPGRADE.id } }
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        try {
            val fresh = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { billingManager.refresh() }
            if (fresh == null) {
                openGraceEpisodeIfWarranted(System.currentTimeMillis())
                return
            }
            val info = Info(billingData = fresh)
            if (info.upgrades.isNotEmpty()) {
                recordProState(info)
            } else {
                // Conclusive full snapshot with no known Pro SKU: open an episode if still in grace.
                openGraceEpisodeIfWarranted(System.currentTimeMillis())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, ERROR) { "Background refresh failed: ${e.asLog()}" }
            openGraceEpisodeIfWarranted(System.currentTimeMillis())
        }
    }

    // Explicit "Restore purchase": query Play now and evaluate Pro from the returned data in the same
    // coroutine (real happens-before). Billing errors propagate so the caller can distinguish "not
    // owned" from "Play unavailable". A grace-only result carries NO upgrades (restore != success).
    suspend fun restorePurchaseNow(): Info {
        log(TAG) { "restorePurchaseNow()" }
        return try {
            val fresh = billingManager.refresh()
            val rawInfo = Info(billingData = fresh)
            if (rawInfo.upgrades.isNotEmpty()) {
                recordProState(rawInfo)
                rawInfo
            } else {
                openGraceEpisodeIfWarranted(System.currentTimeMillis())
                // Apply grace to the RETURNED info so a within-grace empty restore still reports Pro,
                // while keeping upgrades empty so callers treat grace-only as "not a real restore".
                fresh.toUpgradeInfo()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val lastProStateAt = billingCache.lastProStateAt.value()
            if ((System.currentTimeMillis() - lastProStateAt) < graceWindowMs()) {
                log(TAG, VERBOSE) { "restore hit a Play error but we were Pro recently -> grace" }
                openGraceEpisodeIfWarranted(System.currentTimeMillis())
                Info(gracePeriod = true, billingData = null)
            } else {
                throw e
            }
        }
    }

    // Read-only Pro/grace mapping used by the reactive upgradeInfo flow. Branches on MAPPED known Pro
    // SKUs (never raw purchases): an unknown-only purchase must not read as "has purchases" and thereby
    // yield non-Pro while skipping grace. Never stamps the cache (runs on replayed shared-flow data).
    private suspend fun BillingData?.toUpgradeInfo(): Info {
        val now = System.currentTimeMillis()
        val lastProStateAt = billingCache.lastProStateAt.value()
        val info = Info(billingData = this)
        log(TAG) { "toUpgradeInfo(): now=$now, lastProStateAt=$lastProStateAt, upgrades=${info.upgrades}" }
        return when {
            info.upgrades.isNotEmpty() -> info
            (now - lastProStateAt) < graceWindowMs() -> {
                log(TAG, VERBOSE) { "Not pro now, but recently -> grace" }
                Info(gracePeriod = true, billingData = null)
            }
            else -> info
        }
    }

    // Persists "we saw a known Pro purchase" and closes any open episode in one transaction. Callers
    // must only pass Info built from FRESH data. Only a *known* Pro SKU counts; the permanent IAP is
    // preferred so it drives the window length.
    private suspend fun recordProState(info: Info) {
        val sku = preferredProSku(info.upgrades) ?: return
        billingCache.stampProConfirmed(sku.id, System.currentTimeMillis())
    }

    // Opens (set-if-unset) the unconfirmed episode only when we were recently Pro and are still inside
    // the grace window. Fail-quiet: episode bookkeeping must never break the caller.
    private suspend fun openGraceEpisodeIfWarranted(occurredAt: Long) {
        try {
            val lastProAt = billingCache.lastProStateAt.value()
            if (lastProAt <= 0L) return
            if ((occurredAt - lastProAt) >= graceWindowMs()) return
            billingCache.startUnconfirmedEpisode(occurredAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "openGraceEpisodeIfWarranted failed: ${e.asLog()}" }
        }
    }

    // Grace window depends on what was last owned: a permanent one-time purchase gets a long window,
    // a subscription (or an unknown/legacy last SKU) gets the short default.
    private suspend fun graceWindowMs(): Long {
        val lastSku = billingCache.lastProStateSku.value()
        val type = OurSku.PRO_SKUS.singleOrNull { it.id == lastSku }?.type
        return if (type == Sku.Type.IAP) GRACE_PERIOD_IAP_MS else GRACE_PERIOD_MS
    }

    data class SubscriptionStatus(
        val hasBlockingSubscription: Boolean,
    )

    data class Info(
        val gracePeriod: Boolean = false,
        private val billingData: BillingData?,
    ) : UpgradeRepo.Info {

        override val type: UpgradeRepo.Type = UpgradeRepo.Type.GPLAY

        val upgrades: Collection<PurchasedSku> = billingData?.purchases
            ?.map { purchase ->
                purchase.products.mapNotNull { productId ->
                    val sku = OurSku.PRO_SKUS.singleOrNull { it.id == productId }
                    if (sku == null) {
                        log(TAG, ERROR) { "Unknown product: $productId ($purchase)" }
                        return@mapNotNull null
                    }
                    PurchasedSku(sku, purchase)
                }
            }
            ?.flatten()
            ?: emptySet()

        val hasIap: Boolean = upgrades.any { it.sku.type == Sku.Type.IAP }

        private val subscriptions: List<PurchasedSku> = upgrades.filter { it.sku.type == Sku.Type.SUBSCRIPTION }

        val hasSubscription: Boolean = subscriptions.isNotEmpty()

        // Conservative: if ANY owned subscription still auto-renews, treat ownership as renewing. This
        // keeps the switch offer LOCKED (never wrongly unlocked) if a renewal rotated the purchase
        // token and left a stale renewing record alongside a fresh non-renewing one.
        val anySubscriptionRenewing: Boolean = subscriptions.any { it.purchase.isAutoRenewing }

        override val isUpgraded: Boolean = upgrades.isNotEmpty() || gracePeriod

        override val upgradedAt: Instant? = upgrades
            .maxByOrNull { it.purchase.purchaseTime }
            ?.let { Instant.fromEpochMilliseconds(it.purchase.purchaseTime) }
    }


    companion object {
        private const val SITE = "https://play.google.com/store/apps/details?id=eu.darken.butler"
        val GRACE_PERIOD_MS = 7.days.inWholeMilliseconds
        val GRACE_PERIOD_IAP_MS = 30.days.inWholeMilliseconds
        val GRACE_DIAGNOSTICS_AFTER_MS = 24.hours.inWholeMilliseconds
        private val RETRY_DELAY_CAP_MS = 10.minutes.inWholeMilliseconds
        private const val RESTORE_ON_OWNED_TIMEOUT_MS = 15_000L
        private const val REFRESH_TIMEOUT_MS = 30_000L
        val TAG: String = logTag("Upgrade", "Gplay", "Repo")

        internal fun preferredProSku(upgrades: Collection<PurchasedSku>): Sku? =
            upgrades.firstOrNull { it.sku.type == Sku.Type.IAP }?.sku ?: upgrades.firstOrNull()?.sku
    }
}

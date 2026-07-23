package eu.darken.butler.upgrade.core.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient.*
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.PurchaseState
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.upgrade.core.billing.client.BillingClientException
import eu.darken.butler.upgrade.core.billing.client.BillingConnection
import eu.darken.butler.upgrade.core.billing.client.BillingConnectionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    @AppScope private val scope: CoroutineScope,
    connectionProvider: BillingConnectionProvider,
) {

    // Wakes a pending connection-retry backoff early. Zero replay: kicks fired while no retry is
    // waiting are dropped, so a healthy connection can't accumulate stale wake-ups.
    private val connectionKick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Wall-clock times of retryable billing connection failures (see BillingConnectionProvider).
    val connectionFailures: Flow<Long> = connectionProvider.connectionFailures

    private val connection = connectionProvider.connection
        .onEach {
            try {
                it.refreshPurchases()
            } catch (e: Exception) {
                log(TAG, ERROR) { "Initial purchase data refresh failed: ${e.asLog()}" }
            }
        }
        .retryWhen { cause, attempt ->
            // Never give up terminally: the ack collector pins this shareIn forever, so WhileSubscribed
            // can't restart a completed upstream — a swallowed terminal failure (e.g. one transient
            // BILLING_UNAVAILABLE while Play updates itself at boot) would leave billing dead until
            // process restart. Retry with capped backoff instead; Play recovering makes this heal.
            if (cause is CancellationException) {
                false
            } else {
                log(TAG, WARN) { "Billing connection failed (attempt=$attempt), will retry: ${cause.asLog()}" }
                val backoff = (30_000L * (attempt + 1)).coerceAtMost(300_000L)
                // Interruptible backoff: an explicit billing action (restore tap, app-open refresh)
                // wakes the retry immediately — the user may have just fixed Play, don't make them
                // wait out up to 5 minutes for us to notice.
                withTimeoutOrNull(backoff) { connectionKick.first() }
                true
            }
        }
        .setupCommonEventHandlers(TAG) { "connection" }
        .shareIn(scope, WhileSubscribed(3000L, 0L), replay = 1)

    private val purchases = connection
        .flatMapLatest { it.purchases }
        .distinctUntilChanged()
        .setupCommonEventHandlers(TAG) { "purchases" }
        .shareIn(scope, WhileSubscribed(3000L, 0L), replay = 1)

    val billingData: Flow<BillingData> = purchases
        .map { BillingData(purchases = it) }
        .shareIn(scope, WhileSubscribed(3000L, 0L), replay = 1)

    val purchaseFailures: Flow<BillingResult> = connection
        .flatMapLatest { it.purchaseFailures }
        .setupCommonEventHandlers(TAG) { "purchaseFailures" }

    // Tokens we've successfully acknowledged. Only used to lower repeat-log severity: an immutable
    // Purchase snapshot keeps reporting isAcknowledged=false even after Play accepted the ack, so we
    // KEEP issuing idempotent acks (a missed ack -> Play auto-refunds after ~3 days, far worse than a
    // redundant IPC). We never skip on this set.
    private val ackedTokens = ConcurrentHashMap.newKeySet<String>()

    init {
        purchases
            .onEach { purchases ->
                val failures = mutableListOf<Throwable>()
                purchases
                    // Never acknowledge a PENDING purchase; only PURCHASED ones need (and allow) ack.
                    .filter { it.purchaseState == PurchaseState.PURCHASED && !it.isAcknowledged }
                    .forEach { purchase ->
                        if (purchase.purchaseToken in ackedTokens) {
                            log(TAG, VERBOSE) { "Re-acknowledging (stale isAcknowledged): $purchase" }
                        } else {
                            log(TAG, INFO) { "Acknowledging purchase: $purchase" }
                        }
                        try {
                            useConnection { acknowledgePurchase(purchase) }
                            ackedTokens.add(purchase.purchaseToken)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Collect and rethrow after the loop so the outer retryWhen retries; do NOT
                            // add to ackedTokens on failure, so the token stays retryable.
                            failures.add(e)
                        }
                    }
                if (failures.isNotEmpty()) {
                    throw failures.first().also { first -> failures.drop(1).forEach(first::addSuppressed) }
                }
            }
            .setupCommonEventHandlers(TAG) { "connection-acks" }
            .retryWhen { cause, attempt ->
                if (cause is CancellationException) {
                    log(TAG) { "Ack collector cancelled (appScope)." }
                    return@retryWhen false
                }
                // Never terminally give up while an unacked purchase is present — including on
                // BILLING_UNAVAILABLE: leaving a purchase unacknowledged lets Play auto-refund it.
                // Retry with capped backoff; a recovering Play heals this. The replayed shareIn value
                // re-runs the loop, and once every token is acked the loop stops throwing.
                log(TAG, WARN) { "Ack attempt=$attempt failed, will retry: ${cause.asLog()}" }
                delay((3000L * (attempt + 1)).coerceAtMost(ACK_RETRY_CAP_MS))
                true
            }
            .launchIn(scope)
    }

    suspend fun querySubscriptions(): Collection<Purchase> = useConnection {
        log(TAG) { "querySubscriptions()" }
        querySubscriptions()
    }

    suspend fun queryInApps(): Collection<Purchase> = useConnection {
        log(TAG) { "queryInApps()" }
        queryInApps()
    }

    private suspend fun <T> useConnection(action: suspend BillingConnection.() -> T): T {
        // Every explicit billing operation counts as a user-driven "try again NOW".
        connectionKick.tryEmit(Unit)
        return connection
            .map { action(it) }
            .take(1)
            .single()
    }

    suspend fun querySkus(vararg skus: Sku): Collection<SkuDetails> = useConnection {
        log(TAG) { "querySkus(): $skus..." }
        querySkus(*skus).also {
            log(TAG) { "querySkus(): $it" }
        }
    }

    suspend fun startIapFlow(activity: Activity, sku: Sku, offer: Sku.Subscription.Offer?) {
        try {
            useConnection {
                launchBillingFlow(activity, sku, offer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to start IAP flow:\n${e.asLog()}" }
            // Expected environmental/user situations — user-facing handling only, no bug report.
            // ITEM_ALREADY_OWNED is auto-handled by UpgradeRepoGplay (restore instead of error).
            val ignoredCodes = listOf(
                BillingResponseCode.USER_CANCELED,
                BillingResponseCode.BILLING_UNAVAILABLE,
                BillingResponseCode.ERROR,
                BillingResponseCode.ITEM_ALREADY_OWNED,
            )
            when {
                e !is BillingException -> {
                    Bugs.report(RuntimeException("State exception for $sku, U", e))
                }
                e is BillingClientException && !e.result.responseCode.let { ignoredCodes.contains(it) } -> {
                    Bugs.report(RuntimeException("Client exception for $sku", e))
                }
            }

            throw e.tryMapUserFriendly()
        }
    }

    suspend fun refresh(): BillingData {
        log(TAG) { "refresh()" }
        // Query in the caller's context and return the result directly, so callers get the fresh
        // purchases (and any billing error) with a real happens-before instead of racing the shared
        // upgradeInfo replay cache.
        return BillingData(purchases = useConnection { refreshPurchases() })
    }

    companion object {
        internal fun Throwable.tryMapUserFriendly(): Throwable {
            if (this !is BillingClientException) return this

            return when (result.responseCode) {
                BillingResponseCode.USER_CANCELED -> UserCanceledBillingException(this)
                BillingResponseCode.ITEM_ALREADY_OWNED -> ItemAlreadyOwnedBillingException(this)
                BillingResponseCode.BILLING_UNAVAILABLE,
                BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingResponseCode.SERVICE_DISCONNECTED,
                BillingResponseCode.SERVICE_TIMEOUT -> GplayServiceUnavailableException(this)
                else -> this
            }
        }

        private const val ACK_RETRY_CAP_MS = 60_000L

        val TAG: String = logTag("Upgrade", "Gplay", "Billing", "Manager")
    }
}

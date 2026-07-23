package eu.darken.butler.upgrade.core.billing.client

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.Purchase.*
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.upgrade.core.billing.BillingManager.Companion.tryMapUserFriendly
import eu.darken.butler.upgrade.core.billing.Sku
import eu.darken.butler.upgrade.core.billing.SkuDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class BillingConnection(
    private val client: BillingClient,
    val purchaseEvents: Flow<Pair<BillingResult, Collection<Purchase>?>?>,
) {

    private val queryCacheIaps = MutableStateFlow<Collection<Purchase>?>(null)
    private val queryCacheSubs = MutableStateFlow<Collection<Purchase>?>(null)

    val purchases: Flow<Collection<Purchase>> = combine(
        purchaseEvents,
        queryCacheIaps.filterNotNull(),
        queryCacheSubs.filterNotNull(),
    ) { purchaseEvent, iapCache, subCache ->
        // Dedupe by purchaseToken so a fresh authoritative query wins over a stale listener overlay
        // of the same token. Without this, a subscription cancelled in Play keeps its old
        // auto-renewing listener copy unioned on top forever, locking the sub->IAP switch row.
        val byToken = LinkedHashMap<String, Purchase>()

        // Query snapshots (refreshPurchases / querySubscriptions) are authoritative for their type.
        iapCache.forEach { byToken[it.purchaseToken] = it }
        subCache.forEach { byToken[it.purchaseToken] = it }

        // Listener events are add-only and NOT a full snapshot: only surface a listener purchase for
        // a token the authoritative caches don't already cover, so a query that healed renewal state
        // wins, while a genuinely new purchase racing an in-flight query still surfaces.
        purchaseEvent
            ?.takeIf { (result, _) -> result.isSuccess }
            ?.let { (_, purchases) -> purchases?.filter { it.purchaseState == PurchaseState.PURCHASED } }
            ?.forEach { byToken.putIfAbsent(it.purchaseToken, it) }

        byToken.values.sortedByDescending { it.purchaseTime }
    }.setupCommonEventHandlers(TAG) { "purchases" }

    // Non-OK results from onPurchasesUpdated (e.g. async ITEM_ALREADY_OWNED after the Play sheet
    // opened). Consumed by a single persistent collector in UpgradeRepoGplay — not an event bus.
    val purchaseFailures: Flow<BillingResult> = purchaseEvents
        .filterNotNull()
        .filter { (result, _) -> !result.isSuccess }
        .map { (result, _) -> result }

    private suspend fun queryPurchases(@BillingClient.ProductType type: String): Collection<Purchase> {
        val params = QueryPurchasesParams.newBuilder().apply {
            setProductType(type)
        }.build()
        val (billingResult, purchaseData) = client.queryPurchasesAsync(params)

        log(TAG) {
            "queryPurchases($type): code=${billingResult.isSuccess}, message=${billingResult.debugMessage}, purchaseData=${purchaseData}"
        }

        if (!billingResult.isSuccess) {
            log(TAG, WARN) { "queryPurchases() failed" }
            throw BillingClientException(billingResult)
        }

        return purchaseData
    }

    // Returns the freshly queried PURCHASED purchases so callers get a guaranteed happens-before
    // relation instead of racing the shared purchases/upgradeInfo replay caches after a refresh.
    // Tolerant of a single product-type failure: if either query finds a purchase we treat that as
    // authoritative, and only propagate an error when nothing was found AND a query failed — so the
    // caller can tell "not owned" apart from "couldn't verify".
    suspend fun refreshPurchases(): Collection<Purchase> = coroutineScope {
        log(TAG) { "refreshPurchases()" }
        val iapJob = async { queryPurchasedProducts(BillingClient.ProductType.INAPP, queryCacheIaps) }
        val subJob = async { queryPurchasedProducts(BillingClient.ProductType.SUBS, queryCacheSubs) }
        val iap = iapJob.await()
        val sub = subJob.await()
        log(TAG) { "Refreshed IAPs=${iap.getOrNull()}, SUBs=${sub.getOrNull()}" }
        combinePurchaseResults(iap, sub)
    }

    // Never throws except on cancellation, so a single failing product-type query doesn't cancel the
    // sibling query (or the coroutineScope). The exception is already user-friendly-mapped.
    private suspend fun queryPurchasedProducts(
        @BillingClient.ProductType type: String,
        cache: MutableStateFlow<Collection<Purchase>?>,
    ): Result<Collection<Purchase>> = try {
        val purchased = queryPurchases(type).filter { it.purchaseState == PurchaseState.PURCHASED }
        cache.value = purchased
        Result.success(purchased)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The purchases combine gates on both caches being initialized. A failed side that has no
        // data yet counts as "no purchases" so a one-sided failure can't stall the reactive flow
        // (and with it ack + upgradeInfo); later failures keep the last known values instead.
        // compareAndSet so a concurrent successful refresh can't be overwritten with empty.
        cache.compareAndSet(null, emptyList())
        Result.failure(e.tryMapUserFriendly())
    }

    // Fresh SUBS-only read for the subscription->IAP switch gate. Commits the PURCHASED subs into the
    // shared sub cache so the reactive purchases/upgradeInfo heal renewal state (e.g. after the user
    // cancels renewal in Play), and returns ALL current sub purchases INCLUDING pending — the gate
    // must treat a pending or auto-renewing sub as still-owned. Errors PROPAGATE (fail-closed): the
    // caller has to tell "no active sub" apart from "couldn't verify".
    suspend fun querySubscriptions(): Collection<Purchase> {
        log(TAG) { "querySubscriptions()" }
        val subs = queryPurchases(BillingClient.ProductType.SUBS)
        // Heal the entitlement view with confirmed subs only; a pending sub must not grant Pro.
        queryCacheSubs.value = subs.filter { it.purchaseState == PurchaseState.PURCHASED }
        return subs.filter {
            it.purchaseState == PurchaseState.PURCHASED || it.purchaseState == PurchaseState.PENDING
        }
    }

    // Fresh authoritative INAPP-only read for the subscribe gate (symmetric to querySubscriptions()).
    // Unlike refreshPurchases(), this does NOT tolerate a suppressed INAPP failure: the error PROPAGATES
    // so the caller fails closed instead of mistaking "couldn't verify" for "not owned". Returns
    // PURCHASED + PENDING (a pending IAP must still block buying a subscription on top).
    suspend fun queryInApps(): Collection<Purchase> {
        log(TAG) { "queryInApps()" }
        val iaps = queryPurchases(BillingClient.ProductType.INAPP)
        queryCacheIaps.value = iaps.filter { it.purchaseState == PurchaseState.PURCHASED }
        return iaps.filter {
            it.purchaseState == PurchaseState.PURCHASED || it.purchaseState == PurchaseState.PENDING
        }
    }

    suspend fun acknowledgePurchase(purchase: Purchase): BillingResult {
        val ack = AcknowledgePurchaseParams.newBuilder().apply {
            setPurchaseToken(purchase.purchaseToken)
        }.build()

        val ackResult = suspendCoroutine<BillingResult> { continuation ->
            client.acknowledgePurchase(ack) { continuation.resume(it) }
        }
        log(TAG) {
            "acknowledgePurchase(purchase=$purchase): code=${ackResult.responseCode}, message=${ackResult.debugMessage})"
        }

        if (!ackResult.isSuccess) {
            throw BillingClientException(ackResult)
        }
        return ackResult
    }

    suspend fun querySkus(vararg skus: Sku): Collection<SkuDetails> {
        log(TAG) { "querySkus(skus=${skus.joinToString { it.print() }})..." }
        val productList = skus.map { sku ->
            QueryProductDetailsParams.Product.newBuilder().apply {
                setProductId(sku.id)
                setProductType(
                    when (sku.type) {
                        Sku.Type.IAP -> BillingClient.ProductType.INAPP
                        Sku.Type.SUBSCRIPTION -> BillingClient.ProductType.SUBS
                    }
                )
            }.build()
        }

        val params = QueryProductDetailsParams.newBuilder().apply {
            setProductList(productList)
        }.build()

        val (result, details) = suspendCoroutine<Pair<BillingResult, Collection<ProductDetails>?>> { continuation ->
            client.queryProductDetailsAsync(params) { result: BillingResult, details: Collection<ProductDetails> ->
                continuation.resume(result to details)
            }
        }

        log(TAG) {
            "querySkus(skus=${skus.joinToString { it.print() }}): code=${result.responseCode}, debug=${result.debugMessage}), skuDetails=$details"
        }

        if (!result.isSuccess) throw BillingClientException(result)

        if (details.isNullOrEmpty()) {
            throw IllegalStateException("No details available for ${skus.joinToString { "${it.type}-${it.id}" }}")
        }

        return details
            .groupBy { it.productId }
            .mapNotNull { (key, details) ->
                val sku = skus
                    .single { it.id == key }
                val detail = details.single { it.productId == sku.id }

                SkuDetails(sku, detail)
            }
    }

    suspend fun launchBillingFlow(activity: Activity, sku: Sku, targetOffer: Sku.Subscription.Offer?): BillingResult {
        log(TAG) { "launchBillingFlow(activity=$activity, sku=$sku)" }
        if (sku.type == Sku.Type.SUBSCRIPTION) {
            requireNotNull(targetOffer) { "SUB skus require a target offer" }
        }

        val data = querySkus(sku).single { it.sku == sku }

        val params = BillingFlowParams.newBuilder().apply {
            val productDetail = BillingFlowParams.ProductDetailsParams.newBuilder().apply {
                setProductDetails(data.details)
                if (sku is Sku.Subscription && targetOffer != null) {
                    val offer = data.details.subscriptionOfferDetails!!.single {
                        targetOffer.matches(it)
                    }
                    setOfferToken(offer.offerToken)
                }
            }.build()
            setProductDetailsParamsList(listOf(productDetail))
        }.build()

        // launchBillingFlow must run on the main thread (documented BillingClient contract), and its
        // RETURNED result reports whether the flow could be launched at all (DEVELOPER_ERROR,
        // ITEM_ALREADY_OWNED, BILLING_UNAVAILABLE, ...) — failures arrive here, not as exceptions.
        // Throw like the other client calls do, so callers can surface them instead of silence.
        val result = withContext(Dispatchers.Main) {
            client.launchBillingFlow(activity, params)
        }
        log(TAG) {
            "launchBillingFlow(sku=$sku): code=${result.responseCode}, message=${result.debugMessage}"
        }
        if (!result.isSuccess) throw BillingClientException(result)

        return result
    }

    companion object {
        val TAG: String = logTag("Upgrade", "Gplay", "Billing", "ClientConnection")

        // Combines the two product-type query results: a purchase found by either type is
        // authoritative; an error is only propagated when nothing was found, so callers can tell
        // "not owned" apart from "couldn't verify one product type". Pure and unit-tested.
        internal fun combinePurchaseResults(
            iap: Result<Collection<Purchase>>,
            sub: Result<Collection<Purchase>>,
        ): Collection<Purchase> {
            val found = iap.getOrNull().orEmpty() + sub.getOrNull().orEmpty()
            return when {
                found.isNotEmpty() -> found.sortedByDescending { it.purchaseTime }
                else -> {
                    val primary = iap.exceptionOrNull() ?: sub.exceptionOrNull()
                    if (primary != null) {
                        sub.exceptionOrNull()?.takeIf { it !== primary }?.let { primary.addSuppressed(it) }
                        throw primary
                    }
                    emptyList()
                }
            }
        }
    }
}
package eu.darken.butler.upgrade.core.billing.client

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResult
import com.android.billingclient.api.queryPurchasesAsync
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2

class BillingConnectionTest : BaseTest() {

    private fun purchase(time: Long) = mockk<Purchase>().apply { every { purchaseTime } returns time }

    private fun result(code: Int): BillingResult = BillingResult.newBuilder().setResponseCode(code).build()

    @Test fun `a one-sided query failure on a fresh connection does not stall the purchases flow`() = runTest2 {
        mockkStatic("com.android.billingclient.api.BillingClientKotlinKt")
        try {
            val owned = purchase(1_000).apply {
                every { purchaseState } returns Purchase.PurchaseState.PURCHASED
                every { purchaseToken } returns "token-1"
            }
            val client = mockk<BillingClient>()
            // refreshPurchases() queries INAPP first, then SUBS; the mocked call suspends on nothing,
            // so with the single-threaded test dispatcher the answer order is deterministic.
            coEvery { client.queryPurchasesAsync(any<com.android.billingclient.api.QueryPurchasesParams>()) } returnsMany listOf(
                PurchasesResult(result(BillingClient.BillingResponseCode.OK), listOf(owned)),
                PurchasesResult(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE), emptyList()),
            )
            val connection = BillingConnection(client, MutableStateFlow(null))

            val refreshed = connection.refreshPurchases()
            refreshed shouldBe listOf(owned)

            // Regression: the failed SUBS side used to leave its cache null, gating the combine
            // forever - the purchase never reached ack/upgradeInfo despite a successful restore.
            connection.purchases.first() shouldBe listOf(owned)
        } finally {
            unmockkStatic("com.android.billingclient.api.BillingClientKotlinKt")
        }
    }

    @Test fun `querySubscriptions returns purchased and pending subs and propagates errors`() = runTest2 {
        mockkStatic("com.android.billingclient.api.BillingClientKotlinKt")
        try {
            val purchased = purchase(1_000).apply {
                every { purchaseState } returns Purchase.PurchaseState.PURCHASED
                every { purchaseToken } returns "sub-purchased"
            }
            val pending = purchase(2_000).apply {
                every { purchaseState } returns Purchase.PurchaseState.PENDING
                every { purchaseToken } returns "sub-pending"
            }
            val client = mockk<BillingClient>()
            coEvery { client.queryPurchasesAsync(any<com.android.billingclient.api.QueryPurchasesParams>()) } returns
                PurchasesResult(result(BillingClient.BillingResponseCode.OK), listOf(purchased, pending))
            val connection = BillingConnection(client, MutableStateFlow(null))

            // Pending must be reported to the gate (blocking) but only PURCHASED heals the entitlement.
            connection.querySubscriptions().toSet() shouldBe setOf(purchased, pending)
        } finally {
            unmockkStatic("com.android.billingclient.api.BillingClientKotlinKt")
        }
    }

    @Test fun `querySubscriptions propagates a query failure`() = runTest2 {
        mockkStatic("com.android.billingclient.api.BillingClientKotlinKt")
        try {
            val client = mockk<BillingClient>()
            coEvery { client.queryPurchasesAsync(any<com.android.billingclient.api.QueryPurchasesParams>()) } returns
                PurchasesResult(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE), emptyList())
            val connection = BillingConnection(client, MutableStateFlow(null))

            shouldThrow<Exception> { connection.querySubscriptions() }
        } finally {
            unmockkStatic("com.android.billingclient.api.BillingClientKotlinKt")
        }
    }

    @Test fun `queryInApps propagates a query failure so the subscribe gate fails closed`() = runTest2 {
        mockkStatic("com.android.billingclient.api.BillingClientKotlinKt")
        try {
            val client = mockk<BillingClient>()
            coEvery { client.queryPurchasesAsync(any<com.android.billingclient.api.QueryPurchasesParams>()) } returns
                PurchasesResult(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE), emptyList())
            val connection = BillingConnection(client, MutableStateFlow(null))

            shouldThrow<Exception> { connection.queryInApps() }
        } finally {
            unmockkStatic("com.android.billingclient.api.BillingClientKotlinKt")
        }
    }

    @Test fun `combines both product types, newest first`() {
        val older = purchase(1_000)
        val newer = purchase(2_000)

        BillingConnection.combinePurchaseResults(
            iap = Result.success(listOf(older)),
            sub = Result.success(listOf(newer)),
        ) shouldBe listOf(newer, older)
    }

    @Test fun `a single product-type failure does not mask a purchase found by the other`() {
        val owned = purchase(1_000)

        BillingConnection.combinePurchaseResults(
            iap = Result.success(listOf(owned)),
            sub = Result.failure(RuntimeException("SUBS query failed")),
        ) shouldBe listOf(owned)

        BillingConnection.combinePurchaseResults(
            iap = Result.failure(RuntimeException("IAP query failed")),
            sub = Result.success(listOf(owned)),
        ) shouldBe listOf(owned)
    }

    @Test fun `both product types empty returns empty`() {
        BillingConnection.combinePurchaseResults(
            iap = Result.success(emptyList()),
            sub = Result.success(emptyList()),
        ) shouldBe emptyList()
    }

    @Test fun `nothing found but a query failed rethrows the error`() {
        shouldThrow<RuntimeException> {
            BillingConnection.combinePurchaseResults(
                iap = Result.success(emptyList()),
                sub = Result.failure(RuntimeException("SUBS query failed")),
            )
        }
    }

    @Test fun `both queries failing keeps the second error as suppressed`() {
        val iapError = RuntimeException("IAP query failed")
        val subError = RuntimeException("SUBS query failed")

        val thrown = shouldThrow<RuntimeException> {
            BillingConnection.combinePurchaseResults(
                iap = Result.failure(iapError),
                sub = Result.failure(subError),
            )
        }

        thrown shouldBe iapError
        thrown.suppressed.toList() shouldBe listOf(subError)
    }
}

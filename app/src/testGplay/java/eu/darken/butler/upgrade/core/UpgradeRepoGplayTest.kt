package eu.darken.butler.upgrade.core

import com.android.billingclient.api.Purchase
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.core.billing.BillingData
import eu.darken.butler.upgrade.core.billing.BillingManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import kotlin.time.Instant

class UpgradeRepoGplayTest : BaseTest() {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val billingManager = mockk<BillingManager>()
    private val billingCache = mockk<BillingCache>()

    // Builds a repo whose stored last-Pro timestamp is `lastProAt`. billingData is stubbed only
    // because the upgradeInfo flow references it at construction; it is never collected here.
    private fun repo(lastProAt: Long): UpgradeRepoGplay {
        every { billingManager.billingData } returns flowOf(BillingData(emptySet()))
        val lastPro = mockk<DataStoreValue<Long>>(relaxed = true).apply {
            every { flow } returns flowOf(lastProAt)
        }
        every { billingCache.lastProStateAt } returns lastPro
        return UpgradeRepoGplay(scope, dispatcherProvider, billingManager, billingCache)
    }

    private fun proPurchase() = mockk<Purchase>().apply {
        every { products } returns OurSku.PRO_SKUS.map { it.id }
        every { purchaseTime } returns Instant.parse("2024-01-01T00:00:00Z").toEpochMilliseconds()
    }

    @Test fun `test upgrade info pro status mapping`() {
        UpgradeRepoGplay.Info(
            gracePeriod = false,
            billingData = null,
        ).apply {
            isUpgraded shouldBe false
            type shouldBe UpgradeRepo.Type.GPLAY
        }

        UpgradeRepoGplay.Info(
            gracePeriod = true,
            billingData = null,
        ).isUpgraded shouldBe true

        val info = UpgradeRepoGplay.Info(
            gracePeriod = false,
            billingData = BillingData(
                purchases = setOf(
                    mockk<Purchase>().apply {
                        every { products } returns OurSku.PRO_SKUS.map { it.id }
                        every { purchaseTime } returns Instant.parse("2023-12-10T00:00:00Z").toEpochMilliseconds()
                    }
                )
            )
        )
        info.isUpgraded shouldBe true
        info.upgradedAt shouldBe Instant.parse("2023-12-10T00:00:00Z")
    }

    @Test fun `grace period is 7 days`() {
        // Regression pin: this used to be `7 * 24 * 60 * 1000L` (missing a *60) = ~2.8 hours,
        // which dropped paying users to non-Pro within hours of a transient empty/failed billing response.
        UpgradeRepoGplay.GRACE_PERIOD_MS shouldBe 604_800_000L
    }

    @Test fun `restore returns pro when a purchase is found`() = runTest2 {
        coEvery { billingManager.refresh() } returns BillingData(setOf(proPurchase()))

        repo(lastProAt = 0L).restorePurchaseNow().isUpgraded shouldBe true
    }

    @Test fun `restore keeps pro within grace when the query comes back empty`() = runTest2 {
        coEvery { billingManager.refresh() } returns BillingData(emptySet())

        repo(lastProAt = System.currentTimeMillis() - 1_000).restorePurchaseNow().isUpgraded shouldBe true
    }

    @Test fun `restore is not pro when the query is empty and grace has expired`() = runTest2 {
        coEvery { billingManager.refresh() } returns BillingData(emptySet())

        val expired = System.currentTimeMillis() - UpgradeRepoGplay.GRACE_PERIOD_MS - 1_000
        repo(lastProAt = expired).restorePurchaseNow().isUpgraded shouldBe false
    }

    @Test fun `restore keeps pro within grace when the query errors`() = runTest2 {
        coEvery { billingManager.refresh() } throws RuntimeException("Play unavailable")

        repo(lastProAt = System.currentTimeMillis() - 1_000).restorePurchaseNow().isUpgraded shouldBe true
    }

    @Test fun `restore rethrows the error when it happens outside grace`() = runTest2 {
        coEvery { billingManager.refresh() } throws RuntimeException("Play unavailable")

        shouldThrow<RuntimeException> {
            repo(lastProAt = 0L).restorePurchaseNow()
        }
    }
}

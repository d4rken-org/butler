package eu.darken.butler.upgrade.ui

import com.android.billingclient.api.ProductDetails
import eu.darken.butler.upgrade.core.OurSku
import eu.darken.butler.upgrade.core.UpgradeRepoGplay
import eu.darken.butler.upgrade.core.billing.GplayServiceUnavailableException
import eu.darken.butler.upgrade.core.billing.SkuDetails
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2

class UpgradeViewModelTest : BaseTest() {

    private val testDispatcher = StandardTestDispatcher()

    private fun mockRepo(): UpgradeRepoGplay = mockk<UpgradeRepoGplay>(relaxed = true).apply {
        every { upgradeInfo } returns MutableStateFlow(UpgradeRepoGplay.Info(false, null))
        every { wasEverPro } returns MutableStateFlow(false)
        coEvery { querySkus(any()) } returns emptyList()
    }

    private fun buildVm(repo: UpgradeRepoGplay): UpgradeViewModel = UpgradeViewModel(
        dispatcherProvider = TestDispatcherProvider(testDispatcher),
        upgradeRepo = repo,
    )

    @Test
    fun `restore with no purchase emits RestoreFailed`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.restorePurchaseNow() } returns UpgradeRepoGplay.Info(false, null)
        val vm = buildVm(repo)

        val event = async { vm.events.first() }
        vm.restorePurchase()
        advanceUntilIdle()

        event.await() shouldBe UpgradeEvents.RestoreFailed
    }

    @Test
    fun `restore that times out emits RestoreFailed`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.restorePurchaseNow() } coAnswers {
            delay(30_000) // longer than the 15s restore timeout
            UpgradeRepoGplay.Info(gracePeriod = true, billingData = null)
        }
        val vm = buildVm(repo)

        val event = async { vm.events.first() }
        vm.restorePurchase()
        advanceUntilIdle()

        event.await() shouldBe UpgradeEvents.RestoreFailed
    }

    @Test
    fun `restore that errors forwards the error instead of RestoreFailed`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        val boom = IllegalStateException("Play unavailable")
        coEvery { repo.restorePurchaseNow() } throws boom
        val vm = buildVm(repo)

        val forwardedError = async { vm.errorEvents.first() }
        vm.restorePurchase()
        advanceUntilIdle()

        forwardedError.await() shouldBe boom
    }

    @Test
    fun `restore is single-flight, taps during a running restore are ignored`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.restorePurchaseNow() } coAnswers {
            delay(5_000)
            UpgradeRepoGplay.Info(gracePeriod = true, billingData = null)
        }
        val vm = buildVm(repo)

        vm.restorePurchase()
        vm.restorePurchase()
        vm.restorePurchase()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.restorePurchaseNow() }
    }

    @Test
    fun `a finished restore allows a new attempt`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.restorePurchaseNow() } returns UpgradeRepoGplay.Info(false, null)
        val vm = buildVm(repo)

        vm.restorePurchase()
        advanceUntilIdle()
        vm.restorePurchase()
        advanceUntilIdle()

        coVerify(exactly = 2) { repo.restorePurchaseNow() }
    }

    @Test
    fun `previously-pro on this device flows into the banner flag`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        every { repo.wasEverPro } returns MutableStateFlow(true)
        val vm = buildVm(repo)

        val loaded = async { vm.state.first { it?.isLoadingPrices == false } }
        advanceUntilIdle()

        loaded.await()!!.wasPreviouslyPro shouldBe true
    }

    @Test
    fun `price-query error is emitted once, not re-emitted by restore state changes`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        // Both queries fail -> data resolves to null -> the combine's "Play unavailable" emission.
        // The individual query errors are also forwarded (pre-existing behavior), so this pins the
        // count of the aggregate error specifically.
        coEvery { repo.querySkus(any()) } throws RuntimeException("price query failed")
        coEvery { repo.restorePurchaseNow() } returns UpgradeRepoGplay.Info(false, null)
        val vm = buildVm(repo)

        val errors = mutableListOf<Throwable>()
        backgroundScope.launch { vm.errorEvents.collect { errors.add(it) } }
        backgroundScope.launch { vm.state.collect { } }
        advanceUntilIdle()

        // The restoring flag toggling through the state combine must not re-emit the error.
        vm.restorePurchase()
        advanceUntilIdle()
        vm.restorePurchase()
        advanceUntilIdle()

        errors.count { it is GplayServiceUnavailableException } shouldBe 1
    }

    @Test
    fun `banner flag stays off while grace still keeps the user pro`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        // gracePeriod = true => Info.isUpgraded is true even without a current raw purchase.
        every { repo.upgradeInfo } returns MutableStateFlow(UpgradeRepoGplay.Info(gracePeriod = true, billingData = null))
        every { repo.wasEverPro } returns MutableStateFlow(true)
        val vm = buildVm(repo)

        val loaded = async { vm.state.first { it?.isLoadingPrices == false } }
        advanceUntilIdle()

        loaded.await()!!.wasPreviouslyPro shouldBe false
    }

    private fun subDetails(vararg offers: Pair<String, String?>): SkuDetails {
        val offerMocks = offers.map { (basePlan, offer) ->
            mockk<ProductDetails.SubscriptionOfferDetails>(relaxed = true).apply {
                every { basePlanId } returns basePlan
                every { offerId } returns offer
            }
        }
        val details = mockk<ProductDetails>().apply {
            every { subscriptionOfferDetails } returns offerMocks
        }
        return SkuDetails(OurSku.Sub.PRO_UPGRADE, details)
    }

    @Test
    fun `trial is not offered when play withholds the trial offer`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        // Play returned the subscription with only the base plan offer - e.g. an account that is
        // not trial-eligible. `any {}` returning false must not count as "trial available".
        coEvery { repo.querySkus(OurSku.Sub.PRO_UPGRADE) } returns listOf(
            subDetails(OurSku.Sub.PRO_UPGRADE.BASE_OFFER.basePlanId to null),
        )
        val vm = buildVm(repo)

        val loaded = async { vm.state.first { it?.isLoadingPrices == false } }
        advanceUntilIdle()

        loaded.await()!!.apply {
            trialState.available shouldBe false
            subState.available shouldBe true
        }
    }

    @Test
    fun `trial is offered when play returns the trial offer`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.querySkus(OurSku.Sub.PRO_UPGRADE) } returns listOf(
            subDetails(
                OurSku.Sub.PRO_UPGRADE.BASE_OFFER.basePlanId to null,
                OurSku.Sub.PRO_UPGRADE.TRIAL_OFFER.basePlanId to OurSku.Sub.PRO_UPGRADE.TRIAL_OFFER.offerId,
            ),
        )
        val vm = buildVm(repo)

        val loaded = async { vm.state.first { it?.isLoadingPrices == false } }
        advanceUntilIdle()

        loaded.await()!!.trialState.available shouldBe true
    }
}

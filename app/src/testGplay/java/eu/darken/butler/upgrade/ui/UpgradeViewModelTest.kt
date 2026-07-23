package eu.darken.butler.upgrade.ui

import android.app.Activity
import com.android.billingclient.api.ProductDetails
import eu.darken.butler.upgrade.core.OurSku
import eu.darken.butler.upgrade.core.UpgradeRepoGplay
import eu.darken.butler.upgrade.core.billing.SkuDetails
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
        every { isSettled } returns MutableStateFlow(true)
        every { proUnconfirmedSince } returns MutableStateFlow(0L)
        every { graceTick } returns MutableStateFlow(Unit)
        coEvery { querySkus(any()) } returns emptyList()
    }

    private fun buildVm(repo: UpgradeRepoGplay, manage: Boolean = false): UpgradeViewModel = UpgradeViewModel(
        manage = manage,
        dispatcherProvider = TestDispatcherProvider(testDispatcher),
        upgradeRepo = repo,
        webpageTool = mockk(relaxed = true),
    )

    private suspend fun UpgradeViewModel.loaded(): UpgradeUiState.Loaded =
        state.first { it is UpgradeUiState.Loaded } as UpgradeUiState.Loaded

    // --- Restore -------------------------------------------------------------------------------

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
    fun `grace-only restore is not a success`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        // Pro purely via grace -> no owned purchases -> restore must NOT report success.
        coEvery { repo.restorePurchaseNow() } returns UpgradeRepoGplay.Info(gracePeriod = true, billingData = null)
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
            delay(30_000)
            UpgradeRepoGplay.Info(gracePeriod = true, billingData = null)
        }
        val vm = buildVm(repo)

        val event = async { vm.events.first() }
        vm.restorePurchase()
        advanceUntilIdle()

        event.await() shouldBe UpgradeEvents.RestoreFailed
    }

    @Test
    fun `restore that errors forwards the error`() = runTest2(context = testDispatcher) {
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
    fun `restore is single-flight`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.restorePurchaseNow() } coAnswers {
            delay(5_000)
            UpgradeRepoGplay.Info(false, null)
        }
        val vm = buildVm(repo)

        vm.restorePurchase()
        vm.restorePurchase()
        vm.restorePurchase()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.restorePurchaseNow() }
    }

    // --- Sub -> IAP switch gate ----------------------------------------------------------------

    @Test
    fun `switch launches the IAP when no blocking subscription`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.queryCurrentSubscriptions() } returns UpgradeRepoGplay.SubscriptionStatus(false)
        val vm = buildVm(repo)

        vm.onGoIap(mockk<Activity>())
        advanceUntilIdle()

        verify(exactly = 1) { repo.launchBillingFlow(any(), OurSku.Iap.PRO_UPGRADE, null, any()) }
    }

    @Test
    fun `switch is blocked and warns when the subscription still renews`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.queryCurrentSubscriptions() } returns UpgradeRepoGplay.SubscriptionStatus(true)
        val vm = buildVm(repo)

        val event = async { vm.events.first() }
        vm.onGoIap(mockk<Activity>())
        advanceUntilIdle()

        event.await() shouldBe UpgradeEvents.SubscriptionStillRenewing
        verify(exactly = 0) { repo.launchBillingFlow(any(), OurSku.Iap.PRO_UPGRADE, null, any()) }
    }

    @Test
    fun `switch fails closed when the subscription check errors`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.queryCurrentSubscriptions() } throws RuntimeException("Play unavailable")
        val vm = buildVm(repo)

        val event = async { vm.events.first() }
        vm.onGoIap(mockk<Activity>())
        advanceUntilIdle()

        event.await() shouldBe UpgradeEvents.SubscriptionCheckFailed
        verify(exactly = 0) { repo.launchBillingFlow(any(), OurSku.Iap.PRO_UPGRADE, null, any()) }
    }

    @Test
    fun `subscribe fails closed when the IAP-ownership check errors`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.isIapOwnedNow() } throws RuntimeException("Play unavailable")
        val vm = buildVm(repo)

        val event = async { vm.events.first() }
        vm.onGoSubscription(mockk<Activity>())
        advanceUntilIdle()

        event.await() shouldBe UpgradeEvents.SubscriptionCheckFailed
        verify(exactly = 0) { repo.launchBillingFlow(any(), OurSku.Sub.PRO_UPGRADE, any(), any()) }
    }

    // --- State derivation ----------------------------------------------------------------------

    @Test
    fun `previously-pro flows into the banner flag`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        every { repo.wasEverPro } returns MutableStateFlow(true)
        val vm = buildVm(repo)

        val loaded = async { vm.loaded() }
        advanceUntilIdle()

        loaded.await().wasPreviouslyPro shouldBe true
    }

    @Test
    fun `banner flag stays off while grace keeps the user pro`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        every { repo.upgradeInfo } returns MutableStateFlow(UpgradeRepoGplay.Info(gracePeriod = true, billingData = null))
        every { repo.wasEverPro } returns MutableStateFlow(true)
        val vm = buildVm(repo)

        val loaded = async { vm.loaded() }
        advanceUntilIdle()

        loaded.await().wasPreviouslyPro shouldBe false
    }

    @Test
    fun `young grace episode does not show diagnostics`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        every { repo.upgradeInfo } returns MutableStateFlow(UpgradeRepoGplay.Info(gracePeriod = true, billingData = null))
        every { repo.proUnconfirmedSince } returns MutableStateFlow(System.currentTimeMillis())
        val vm = buildVm(repo)

        val loaded = async { vm.loaded() }
        advanceUntilIdle()

        loaded.await().grace?.showDiagnostics shouldBe false
    }

    @Test
    fun `aged grace episode shows diagnostics`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        every { repo.upgradeInfo } returns MutableStateFlow(UpgradeRepoGplay.Info(gracePeriod = true, billingData = null))
        val aged = System.currentTimeMillis() - UpgradeRepoGplay.GRACE_DIAGNOSTICS_AFTER_MS - 1_000
        every { repo.proUnconfirmedSince } returns MutableStateFlow(aged)
        val vm = buildVm(repo)

        val loaded = async { vm.loaded() }
        advanceUntilIdle()

        loaded.await().grace?.showDiagnostics shouldBe true
    }

    @Test
    fun `both price queries failing yields Unavailable for a non-owner`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        coEvery { repo.querySkus(any()) } throws RuntimeException("price query failed")
        val vm = buildVm(repo)

        val unavailable = async { vm.state.first { it is UpgradeUiState.Unavailable } }
        advanceUntilIdle()

        unavailable.await().shouldBeInstanceOf<UpgradeUiState.Unavailable>()
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
        coEvery { repo.querySkus(OurSku.Sub.PRO_UPGRADE) } returns listOf(
            subDetails(OurSku.Sub.PRO_UPGRADE.BASE_OFFER.basePlanId to null),
        )
        val vm = buildVm(repo)

        val loaded = async { vm.loaded() }
        advanceUntilIdle()

        loaded.await().subscriptionAction shouldBe UpgradeUiState.SubscriptionAction.STANDARD
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

        val loaded = async { vm.loaded() }
        advanceUntilIdle()

        loaded.await().subscriptionAction shouldBe UpgradeUiState.SubscriptionAction.TRIAL
    }
}

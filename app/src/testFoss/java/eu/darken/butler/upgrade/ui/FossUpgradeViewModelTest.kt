package eu.darken.butler.upgrade.ui

import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.R
import eu.darken.butler.common.navigation.NavEvent
import eu.darken.butler.upgrade.core.FossUpgrade
import eu.darken.butler.upgrade.core.UpgradeRepoFoss
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import testhelpers.BaseTest
import testhelpers.TestApplication
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.time.Duration
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class FossUpgradeViewModelTest : BaseTest() {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private val supporterSince = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun upgradedInfo() = UpgradeRepoFoss.Info(
        isPro = true,
        upgradedAt = supporterSince,
        fossUpgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
    )

    private fun mockRepo(
        info: MutableStateFlow<UpgradeRepoFoss.Info> = MutableStateFlow(UpgradeRepoFoss.Info()),
    ): UpgradeRepoFoss = mockk<UpgradeRepoFoss>(relaxed = true).apply {
        every { upgradeInfo } returns info
    }

    private fun buildVm(
        repo: UpgradeRepoFoss = mockRepo(),
        handle: SavedStateHandle = SavedStateHandle(),
        manage: Boolean = true,
    ) = UpgradeViewModel(
        manage = manage,
        dispatcherProvider = TestDispatcherProvider(testDispatcher),
        savedStateHandle = handle,
        upgradeRepo = repo,
    )

    @Test
    fun `manage route shows the free status to non-upgraded users`() = runTest2(context = testDispatcher) {
        val vm = buildVm()

        val state = async { vm.state.first { it != null } }
        advanceUntilIdle()

        state.await()?.view shouldBe FossUpgradeView.STATUS_FREE
    }

    @Test
    fun `manage route shows the upgraded status to supporters`() = runTest2(context = testDispatcher) {
        val vm = buildVm(repo = mockRepo(MutableStateFlow(upgradedInfo())))

        val state = async { vm.state.first { it != null } }
        advanceUntilIdle()

        state.await()?.view shouldBe FossUpgradeView.STATUS_UPGRADED
    }

    @Test
    fun `supporterSince reflects the repo's upgradedAt`() = runTest2(context = testDispatcher) {
        // Derived in the same emission as the view: the upgraded status must never render a frame
        // without the date it is supposed to carry.
        val vm = buildVm(repo = mockRepo(MutableStateFlow(upgradedInfo())))

        val state = async { vm.state.first { it != null } }
        advanceUntilIdle()

        state.await() shouldBe UpgradeViewModel.State(
            view = FossUpgradeView.STATUS_UPGRADED,
            supporterSince = supporterSince,
        )
    }

    @Test
    fun `the default route shows the pitch`() = runTest2(context = testDispatcher) {
        val vm = buildVm(manage = false)

        val state = async { vm.state.first { it != null } }
        advanceUntilIdle()

        state.await()?.view shouldBe FossUpgradeView.PITCH
    }

    @Test
    fun `asking for upgrade options switches the free status to the pitch`() = runTest2(context = testDispatcher) {
        val vm = buildVm()

        val freeState = async { vm.state.first { it != null } }
        advanceUntilIdle()
        freeState.await()?.view shouldBe FossUpgradeView.STATUS_FREE

        val pitchState = async { vm.state.first { it?.view == FossUpgradeView.PITCH } }
        vm.onShowUpgradeOptions()
        advanceUntilIdle()

        pitchState.await()?.view shouldBe FossUpgradeView.PITCH
    }

    @Test
    fun `the upgrade-options choice survives process recreation`() = runTest2(context = testDispatcher) {
        val handle = SavedStateHandle()
        buildVm(handle = handle).onShowUpgradeOptions()
        advanceUntilIdle()

        // Same handle, fresh ViewModel — as after the process was killed on the pitch.
        val recreatedVm = buildVm(handle = handle)
        val state = async { recreatedVm.state.first { it != null } }
        advanceUntilIdle()

        state.await()?.view shouldBe FossUpgradeView.PITCH
    }

    @Test
    fun `completing the upgrade lands on the upgraded status even from the pitch`() = runTest2(
        context = testDispatcher,
    ) {
        val info = MutableStateFlow(UpgradeRepoFoss.Info())
        val vm = buildVm(repo = mockRepo(info))
        vm.onShowUpgradeOptions()

        val pitchState = async { vm.state.first { it != null } }
        advanceUntilIdle()
        pitchState.await()?.view shouldBe FossUpgradeView.PITCH

        val upgradedState = async { vm.state.first { it?.view == FossUpgradeView.STATUS_UPGRADED } }
        info.value = upgradedInfo()
        advanceUntilIdle()

        upgradedState.await()?.view shouldBe FossUpgradeView.STATUS_UPGRADED
    }

    @Test
    fun `the default route closes itself once the sponsorship lands`() = runTest2(context = testDispatcher) {
        val info = MutableStateFlow(UpgradeRepoFoss.Info())
        val vm = buildVm(repo = mockRepo(info), manage = false)

        val navEvents = mutableListOf<NavEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { vm.navEvents.collect { navEvents.add(it) } }
        advanceUntilIdle()
        navEvents.shouldBeEmpty()

        info.value = upgradedInfo()
        advanceUntilIdle()

        // Exactly one: DestinationUpgrade promises the screen closes itself, not that it keeps
        // firing nav-ups for every later emission.
        navEvents shouldBe listOf(NavEvent.Up)
        collector.cancel()
    }

    @Test
    fun `the manage route keeps an upgraded user on the screen`() = runTest2(context = testDispatcher) {
        val vm = buildVm(repo = mockRepo(MutableStateFlow(upgradedInfo())))

        val navEvents = mutableListOf<NavEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { vm.navEvents.collect { navEvents.add(it) } }
        advanceUntilIdle()

        navEvents.shouldBeEmpty()
        collector.cancel()
    }

    @Test
    fun `a second sponsor tap does not open a second page`() = runTest2(context = testDispatcher) {
        // Single-flight: the second tap would also restamp the timer and reset the window the
        // return check evaluates.
        val repo = mockRepo()
        val vm = buildVm(repo = repo, manage = false)

        vm.openSponsor()
        vm.openSponsor()
        advanceUntilIdle()

        verify(exactly = 1) { repo.openGithubSponsorsPage() }
    }

    @Test
    fun `a too-quick sponsor return only nudges, it does not upgrade`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        val vm = buildVm(repo = repo, manage = false)

        val nudge = async { vm.snackbarEvent.first() }
        vm.openSponsor()
        vm.checkSponsorReturn()
        advanceUntilIdle()

        nudge.await() shouldBe R.string.upgrade_screen_sponsor_too_fast
        coVerify(exactly = 0) { repo.persistUpgrade() }
    }

    @Test
    fun `a sponsor return stays silent for already upgraded users`() = runTest2(context = testDispatcher) {
        // The already-upgraded check runs BEFORE the elapsed time is looked at: an armed stale key
        // must never rewrite the supporter-since date, however long the visit was.
        val repo = mockRepo(MutableStateFlow(upgradedInfo()))
        val vm = buildVm(repo = repo)

        val nudges = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { vm.snackbarEvent.collect { nudges.add(it) } }

        vm.openSponsor()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()
        advanceUntilIdle()

        nudges.shouldBeEmpty()
        coVerify(exactly = 0) { repo.persistUpgrade() }
        collector.cancel()
    }

    @Test
    fun `the recurring sponsor button never arms the unlock check`() = runTest2(context = testDispatcher) {
        // The unarmed entrypoint: a supporter donating again must not re-run the unlock and
        // rewrite the supporter-since date shown right above the button.
        val repo = mockRepo(MutableStateFlow(upgradedInfo()))
        val vm = buildVm(repo = repo)

        vm.openRecurringSponsor()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.hasPendingSponsorLaunch() shouldBe false

        vm.checkSponsorReturn()
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.persistUpgrade() }
    }

    @Test
    fun `a sponsor return after the delay persists the upgrade`() = runTest2(context = testDispatcher) {
        val repo = mockRepo()
        val vm = buildVm(repo = repo, manage = false)

        val thanks = async { vm.toastEvent.first() }
        vm.openSponsor()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()
        advanceUntilIdle()

        thanks.await() shouldBe R.string.upgrade_screen_thanks_toast
        coVerify(exactly = 1) { repo.persistUpgrade() }
    }

    /**
     * Process death between the sponsor launch and the return: the screen's in-memory return
     * tracker is gone, so the handle-backed pending launch has to carry the state across. Without
     * it the very first return after a recreation is dropped and the supporter never gets unlocked.
     */
    @Test
    fun `a sponsor return after process recreation still persists the upgrade`() = runTest2(
        context = testDispatcher,
    ) {
        val handle = SavedStateHandle()
        buildVm(handle = handle, manage = false).openSponsor()
        advanceUntilIdle()

        // Same handle, fresh ViewModel — as after the process was killed while the browser was up.
        val repo = mockRepo()
        val recreatedVm = buildVm(repo = repo, handle = handle, manage = false)
        recreatedVm.hasPendingSponsorLaunch() shouldBe true

        val thanks = async { recreatedVm.toastEvent.first() }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        recreatedVm.checkSponsorReturn()
        advanceUntilIdle()

        thanks.await() shouldBe R.string.upgrade_screen_thanks_toast
        coVerify(exactly = 1) { repo.persistUpgrade() }
        // Consumed: a later resume must not re-run the unlock.
        recreatedVm.hasPendingSponsorLaunch() shouldBe false
    }
}

package eu.darken.butler.upgrade.ui

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.R
import eu.darken.butler.common.navigation.NavEvent
import eu.darken.butler.upgrade.core.FossUpgrade
import eu.darken.butler.upgrade.core.UpgradeRepoFoss
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
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
import java.io.IOException
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
        // Explicit: a relaxed mock would answer the launch with `false`, which now means "no page
        // opened" and would leave every armed-path test unarmed.
        every { openGithubSponsorsPage() } returns true
        // Explicit: a relaxed mock would answer the Boolean with false, i.e. "record already
        // existed", silently turning every thanks-toast assertion below into a no-op.
        coEvery { persistUpgrade() } returns true
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
    fun `a sponsor page that never opened arms nothing and a later retry still works`() = runTest2(
        context = testDispatcher,
    ) {
        // A silently failed launch must not leave the heuristic armed: an unrelated later
        // background round-trip would otherwise hand out supporter status for free.
        val repo = mockRepo()
        every { repo.openGithubSponsorsPage() } returns false
        val vm = buildVm(repo = repo, manage = false)

        vm.openSponsor()
        advanceUntilIdle()

        vm.hasPendingSponsorLaunch() shouldBe false

        // And the failure must not brick the button either — the next working attempt arms as usual.
        every { repo.openGithubSponsorsPage() } returns true
        vm.openSponsor()
        advanceUntilIdle()

        vm.hasPendingSponsorLaunch() shouldBe true
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
        // The already-upgraded check runs BEFORE the elapsed time is looked at. The store transaction
        // keeps the existing record either way, so this is about the feedback: no redundant write
        // attempt and no thanks toast for an unlock that already happened, however long the visit was.
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
        // The unarmed entrypoint: a supporter donating again must not re-run the unlock heuristic —
        // no write attempt, and no thanks toast right above the date they already have.
        val repo = mockRepo(MutableStateFlow(upgradedInfo()))
        val vm = buildVm(repo = repo)

        vm.openRecurringSponsor()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.hasPendingSponsorLaunch() shouldBe false

        vm.checkSponsorReturn()
        advanceUntilIdle()

        // Unarmed, not inert: the button still has to take the supporter to the sponsor page.
        verify(exactly = 1) { repo.openGithubSponsorsPage() }
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

    @Test
    fun `a sponsor return whose record already existed stays quiet`() = runTest2(context = testDispatcher) {
        // The isPro fast path reads an entitlement emission that can be stale, so a supporter's
        // return can get past it. Only the store transaction knows the record is already there — it
        // keeps it and reports "not created", and there is no unlock to thank anyone for.
        val repo = mockRepo()
        coEvery { repo.persistUpgrade() } returns false
        val vm = buildVm(repo = repo, manage = false)

        val nudges = mutableListOf<Int>()
        val thanks = mutableListOf<Int>()
        val snackbarCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            vm.snackbarEvent.collect { nudges.add(it) }
        }
        val toastCollector = launch(start = CoroutineStart.UNDISPATCHED) { vm.toastEvent.collect { thanks.add(it) } }

        vm.openSponsor()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.persistUpgrade() }
        thanks.shouldBeEmpty()
        nudges.shouldBeEmpty()
        // Consumed: the visit was evaluated, there is nothing left to retry.
        vm.hasPendingSponsorLaunch() shouldBe false

        snackbarCollector.cancel()
        toastCollector.cancel()
    }

    @Test
    fun `a failed persist restores the pending sponsor launch`() = runTest2(context = testDispatcher) {
        // The marker is consumed before the write. If the write then fails, dropping it would eat a
        // valid sponsor visit for good — the next return/resume has to be able to retry the unlock.
        val repo = mockRepo()
        coEvery { repo.persistUpgrade() } throws IOException("write failed")
        val vm = buildVm(repo = repo, manage = false)

        val thanks = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val toastCollector = launch(start = CoroutineStart.UNDISPATCHED) { vm.toastEvent.collect { thanks.add(it) } }
        val errorCollector = launch(start = CoroutineStart.UNDISPATCHED) { vm.errorEvents.collect { errors.add(it) } }

        vm.openSponsor()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()
        advanceUntilIdle()

        vm.hasPendingSponsorLaunch() shouldBe true
        thanks.shouldBeEmpty()
        // Rethrown, not swallowed: the failure still travels the normal error path.
        errors.single().shouldBeInstanceOf<IOException>()

        toastCollector.cancel()
        errorCollector.cancel()
    }

    @Test
    fun `a thrown entitlement read restores the pending sponsor launch`() = runTest2(context = testDispatcher) {
        // The guard's entitlement read happens after the marker was consumed, so it can eat the
        // sponsor visit just as a failed write can. Installed after arming: the ViewModel's init
        // collector already holds the working flow, so the only failing read is the guard's. The repo
        // now settles its own cache read failures into error Infos, so this throw stands for whatever
        // still can throw into the guard's first() — the VM-level contract is the same either way.
        val repo = mockRepo()
        val vm = buildVm(repo = repo, manage = false)

        val thanks = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val toastCollector = launch(start = CoroutineStart.UNDISPATCHED) { vm.toastEvent.collect { thanks.add(it) } }
        val errorCollector = launch(start = CoroutineStart.UNDISPATCHED) { vm.errorEvents.collect { errors.add(it) } }

        vm.openSponsor()
        advanceUntilIdle()
        every { repo.upgradeInfo } returns flow { throw IOException("read failed") }

        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()
        advanceUntilIdle()

        vm.hasPendingSponsorLaunch() shouldBe true
        thanks.shouldBeEmpty()
        coVerify(exactly = 0) { repo.persistUpgrade() }
        errors.single().shouldBeInstanceOf<IOException>()

        toastCollector.cancel()
        errorCollector.cancel()
    }

    @Test
    fun `a hung entitlement read releases the visit when the screen dies`() = runTest2(context = testDispatcher) {
        // A read that never answers holds the check open with the marker already consumed. When the
        // screen goes away the coroutine is cancelled — the catch's cancellation path has to hand
        // the sponsor visit back, otherwise it is lost with nothing to retry from.
        val repo = mockRepo()
        val vm = buildVm(repo = repo, manage = false)

        vm.openSponsor()
        advanceUntilIdle()
        every { repo.upgradeInfo } returns flow { awaitCancellation() }

        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()
        advanceUntilIdle()
        // Suspended inside the guard's read, marker already consumed.
        vm.hasPendingSponsorLaunch() shouldBe false

        // The ViewModel going away: vmScope shares viewModelScope's job, so this is the cleared VM.
        vm.vmScope.cancel()
        advanceUntilIdle()

        vm.hasPendingSponsorLaunch() shouldBe true
        coVerify(exactly = 0) { repo.persistUpgrade() }
    }

    @Test
    fun `a newer sponsor launch survives a failed older attempt`() = runTest2(context = testDispatcher) {
        // The restore must not clobber a launch armed while the old attempt was still suspended:
        // the newer visit is the one the user is actually waiting on.
        val repo = mockRepo()
        val gate = CompletableDeferred<Unit>()
        coEvery { repo.persistUpgrade() } coAnswers {
            gate.await()
            throw IOException("write failed")
        }
        val handle = SavedStateHandle()
        val vm = buildVm(repo = repo, handle = handle, manage = false)

        val errors = mutableListOf<Throwable>()
        val errorCollector = launch(start = CoroutineStart.UNDISPATCHED) { vm.errorEvents.collect { errors.add(it) } }

        vm.openSponsor()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()
        advanceUntilIdle()
        // Consumed and parked in the write.
        vm.hasPendingSponsorLaunch() shouldBe false

        // A second sponsor visit while the first attempt is still hanging.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        val newerPressedAt = SystemClock.elapsedRealtime()
        vm.openSponsor()
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        vm.hasPendingSponsorLaunch() shouldBe true
        // Mirrors the ViewModel's private KEY_SPONSOR_PRESSED_AT: the newer timestamp must still be
        // the one stored, the failed older attempt must not have written its own back over it.
        handle.get<Long>("sponsor_pressed_at") shouldBe newerPressedAt
        errors.single().shouldBeInstanceOf<IOException>()

        errorCollector.cancel()
    }

    @Test
    fun `a settled error Info raises an error event`() = runTest2(context = testDispatcher) {
        // The repo settles a failed entitlement read into an error Info instead of dying, so the
        // ViewModel is the only place left that can turn that failure into something the user sees.
        val info = MutableStateFlow(UpgradeRepoFoss.Info())
        val vm = buildVm(repo = mockRepo(info))

        val errors = mutableListOf<Throwable>()
        val errorCollector = launch(start = CoroutineStart.UNDISPATCHED) { vm.errorEvents.collect { errors.add(it) } }
        advanceUntilIdle()
        errors.shouldBeEmpty()

        val first = IOException("cache broken")
        info.value = UpgradeRepoFoss.Info(error = first)
        advanceUntilIdle()

        errors shouldBe listOf(first)

        // Deliberately unsuppressed: butler refreshes the entitlement on every foreground transition,
        // so a still-broken store raises the failure again on each resume while the screen is open,
        // instead of going quiet after the first one.
        val second = IOException("cache still broken")
        info.value = UpgradeRepoFoss.Info(error = second)
        advanceUntilIdle()

        errors shouldBe listOf(first, second)

        errorCollector.cancel()
    }

    @Test
    fun `a corrupt record keeps failing without losing the visit`() = runTest2(context = testDispatcher) {
        // The accepted cost of the no-clobber invariant: FossCache leaves the decode fallback off, so
        // a corrupt stored record makes the persist transaction THROW instead of silently replacing
        // it. The read leg no longer propagates that throw — the repo settles it into an error Info —
        // so the write is where a corrupt record now surfaces. Every armed resume repeats the same
        // sequence: persist throws, marker restored, error surfaced. Deliberate: an honest repeated
        // signal, nothing destroyed, recovery stays an explicit user action.
        val readError = IOException("cache broken")
        val repo = mockRepo(MutableStateFlow(UpgradeRepoFoss.Info(error = readError)))
        coEvery { repo.persistUpgrade() } throws SerializationException("corrupt record")
        val vm = buildVm(repo = repo, manage = false)

        val thanks = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val toastCollector = launch(start = CoroutineStart.UNDISPATCHED) { vm.toastEvent.collect { thanks.add(it) } }
        val errorCollector = launch(start = CoroutineStart.UNDISPATCHED) { vm.errorEvents.collect { errors.add(it) } }

        vm.openSponsor()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        vm.checkSponsorReturn()
        advanceUntilIdle()

        // Restored, so the second resume finds the visit still armed and re-runs the same failure.
        vm.hasPendingSponsorLaunch() shouldBe true
        thanks.shouldBeEmpty()
        coVerify(exactly = 1) { repo.persistUpgrade() }
        // Filtered: the settled read error rides the same channel (the init collector raises it), the
        // persist failures are the ones this test is about.
        errors.filterIsInstance<SerializationException>().size shouldBe 1

        vm.checkSponsorReturn()
        advanceUntilIdle()

        vm.hasPendingSponsorLaunch() shouldBe true
        thanks.shouldBeEmpty()
        coVerify(exactly = 2) { repo.persistUpgrade() }
        errors.filterIsInstance<SerializationException>().size shouldBe 2

        toastCollector.cancel()
        errorCollector.cancel()
    }
}

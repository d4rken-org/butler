package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.hidden.ExplorerHiddenPathsRepo
import eu.darken.butler.upgrade.UpgradeRepo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Duration.Companion.seconds

class ExplorerHiddenPathsControllerTest : BaseTest() {

    private fun path(name: String) = LocalPath.build(File("/tmp/hide-test", name))

    private fun List<APath<*>>.paths() = map { it.path }

    private fun mockRepo(): ExplorerHiddenPathsRepo = mockk<ExplorerHiddenPathsRepo>().apply {
        coEvery { hideAll(any()) } answers { firstArg() }
        coEvery { unhideAll(any()) } just Runs
    }

    private var upgradeScreens = 0
    private var stopShowingHiddenCalls = 0

    private fun CoroutineScope.controller(
        repo: ExplorerHiddenPathsRepo = mockRepo(),
        upgradeRepo: UpgradeRepo = FakeUpgradeRepo(pro = true),
        isPickerActive: () -> Boolean = { false },
        isShowingHidden: () -> Boolean = { false },
    ) = ExplorerHiddenPathsController(
        hiddenPathsRepo = repo,
        upgradeRepo = upgradeRepo,
        scope = this,
        doLaunch = { block -> launch { block() } },
        isPickerActive = isPickerActive,
        isShowingHidden = isShowingHidden,
        stopShowingHidden = { stopShowingHiddenCalls++ },
        navToUpgrade = { upgradeScreens++ },
        tag = "test",
    )

    @Test
    fun `a free user is sent to the upgrade screen and nothing is stored`() = runTest {
        val repo = mockRepo()
        var hidden = false
        val controller = controller(repo = repo, upgradeRepo = FakeUpgradeRepo(pro = false))

        controller.hide(listOf(path("a")), onHidden = { hidden = true })
        runCurrent()

        upgradeScreens shouldBe 1
        hidden shouldBe false
        coVerify(exactly = 0) { repo.hideAll(any()) }
        controller.feedback.value shouldBe null
    }

    @Test
    fun `repeated taps while the gate waits for billing open the upgrade screen once`() = runTest {
        val repo = mockRepo()
        val controller = controller(repo = repo, upgradeRepo = FakeUpgradeRepo(pro = false, settled = false))

        controller.hide(listOf(path("a")))
        runCurrent()
        controller.hide(listOf(path("a")))
        runCurrent()
        advanceTimeBy(10.seconds)
        runCurrent()

        upgradeScreens shouldBe 1
        coVerify(exactly = 0) { repo.hideAll(any()) }
    }

    @Test
    fun `a Pro user stores the paths and gets feedback`() = runTest {
        val repo = mockRepo()
        var hidden = false
        val controller = controller(repo = repo)

        controller.hide(listOf(path("a"), path("b")), onHidden = { hidden = true })
        runCurrent()

        upgradeScreens shouldBe 0
        hidden shouldBe true
        coVerify(exactly = 1) { repo.hideAll(match { it.paths() == listOf(path("a").path, path("b").path) }) }
        val feedback = controller.feedback.value!!
        feedback.count shouldBe 2
        feedback.displayName.get(mockk()) shouldBe "a"
        feedback.showHiddenOn shouldBe false
    }

    @Test
    fun `feedback records that the tab showed hidden items at hide time`() = runTest {
        var showingHidden = true
        val controller = controller(isShowingHidden = { showingHidden })

        controller.hide(listOf(path("a")))
        runCurrent()
        showingHidden = false

        controller.feedback.value!!.showHiddenOn shouldBe true
    }

    @Test
    fun `nothing newly hidden clears the feedback instead of confirming`() = runTest {
        val repo = mockRepo()
        coEvery { repo.hideAll(any()) } returns emptyList<APath<*>>()
        val controller = controller(repo = repo)

        controller.hide(listOf(path("a")))
        runCurrent()

        controller.feedback.value shouldBe null
    }

    @Test
    fun `the feedback action stops showing hidden items and clears the bar`() = runTest {
        val controller = controller(isShowingHidden = { true })
        controller.hide(listOf(path("a")))
        runCurrent()

        controller.onFeedbackAction()
        runCurrent()

        stopShowingHiddenCalls shouldBe 1
        controller.feedback.value shouldBe null
    }

    @Test
    fun `feedback expires after the timeout`() = runTest {
        val controller = controller()
        controller.hide(listOf(path("a")))
        runCurrent()
        controller.feedback.value shouldNotBe null

        advanceTimeBy(ExplorerHiddenPathsController.FEEDBACK_TIMEOUT)
        runCurrent()

        controller.feedback.value shouldBe null
    }

    @Test
    fun `newer feedback survives the superseded one's timer`() = runTest {
        val controller = controller()
        controller.hide(listOf(path("first")))
        runCurrent()
        val first = controller.feedback.value!!

        advanceTimeBy(2.seconds)
        controller.hide(listOf(path("second")))
        runCurrent()
        val second = controller.feedback.value!!
        second.id shouldNotBe first.id

        // Past the first timer, short of the second.
        advanceTimeBy(3.5.seconds)
        runCurrent()
        controller.feedback.value shouldBe second
    }

    @Test
    fun `hide and unhide are ignored in a picker`() = runTest {
        val repo = mockRepo()
        val controller = controller(repo = repo, isPickerActive = { true })

        controller.hide(listOf(path("a")))
        controller.unhide(listOf(path("b")))
        runCurrent()

        controller.feedback.value shouldBe null
        upgradeScreens shouldBe 0
        coVerify(exactly = 0) { repo.hideAll(any()) }
        coVerify(exactly = 0) { repo.unhideAll(any()) }
    }

    @Test
    fun `a free user can unhide and nothing is added`() = runTest {
        val repo = mockRepo()
        val controller = controller(repo = repo, upgradeRepo = FakeUpgradeRepo(pro = false))

        controller.unhide(listOf(path("a")))
        runCurrent()

        upgradeScreens shouldBe 0
        coVerify(exactly = 1) { repo.unhideAll(match { it.paths() == listOf(path("a").path) }) }
        coVerify(exactly = 0) { repo.hideAll(any()) }
        controller.feedback.value shouldBe null
    }
}

package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a cancelled load does to the tab's own navigation state. The engine reports what it restored,
 * the workspace has to make its target and history agree with that content again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerWorkspaceCancelTest {

    private val home = ExplorerNavigation.Target.Home
    private val downloadTarget = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Download"))
    private val picturesTarget = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Pictures"))

    private val engineLocation = MutableStateFlow(BrowsingEngine.State())
    private val engine = mockk<BrowsingEngine>(relaxed = true).apply {
        every { location } returns engineLocation
    }

    private fun TestScope.startedOnHome() = testExplorerWorkspace(
        ExplorerArguments.Default(startTarget = ExplorerStartTarget.HOME),
        UnconfinedTestDispatcher(testScheduler),
        browsingEngine = engine,
    )

    private suspend fun ExplorerWorkspace.ready() = state.first() as ExplorerWorkspace.State.Ready

    /** Home, loaded and settled: the state the engine would restore a cancelled navigation to. */
    private fun settledHome() = BrowsingEngine.State(
        location = ExplorerLocation.Home(items = emptyList(), progress = null),
        breadcrumbs = emptyList(),
        target = home,
    )

    private fun settled(target: ExplorerNavigation.Target) = BrowsingEngine.State(
        location = when (target) {
            is ExplorerNavigation.Target.Directory -> ExplorerLocation.Directory(
                path = target.path,
                items = emptyList(),
                progress = null,
            )
            else -> ExplorerLocation.Home(items = emptyList(), progress = null)
        },
        breadcrumbs = emptyList(),
        target = target,
    )

    @Test
    fun `a cancelled navigation rolls the history back to the restored target`() = runTest {
        coEvery { engine.cancelLoad() } returns BrowsingEngine.CancelResult.NavigationRestored(home)
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = settledHome()
            advanceUntilIdle()

            workspace.navigate(downloadTarget)
            advanceUntilIdle()
            workspace.ready().run {
                currentTarget shouldBe downloadTarget
                navigationHistory shouldBe listOf(home, downloadTarget)
                historyIndex shouldBe 1
            }

            workspace.navigate(ExplorerNavigation.Cancel)
            advanceUntilIdle()
            workspace.ready().run {
                currentTarget shouldBe home
                navigationHistory shouldBe listOf(home)
                historyIndex shouldBe 0
                canGoBack shouldBe false
            }
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a cancelled refresh leaves the history alone`() = runTest {
        coEvery { engine.cancelLoad() } returns BrowsingEngine.CancelResult.RefreshCancelled
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            engineLocation.value = settledHome()
            advanceUntilIdle()
            workspace.navigate(downloadTarget)
            advanceUntilIdle()

            workspace.navigate(ExplorerNavigation.Cancel)
            advanceUntilIdle()
            workspace.ready().run {
                currentTarget shouldBe downloadTarget
                navigationHistory shouldBe listOf(home, downloadTarget)
                historyIndex shouldBe 1
            }
        } finally {
            workspace.release()
        }
    }

    /**
     * Pins the invariant a cancel restores to: the entry the history index points at is the target
     * the tab is on. Going back writes both, and a settle collected between two separate writes
     * would pair the destination with the index of the entry it just left.
     *
     * The engine here settles as soon as it is asked to load; the target the user cancels never
     * settles. Note that the collection of that settle cannot be forced between two writes of the
     * same suspend function in a single-threaded test - unconfined resumptions are queued in the
     * thread's event loop - so this is a behaviour pin, not a reproduction of the interleaving.
     */
    @Test
    fun `going back pairs the restored content with the entry it belongs to`() = runTest {
        every { engine.setTarget(any()) } answers {
            val requested = firstArg<ExplorerNavigation.Target>()
            if (requested != picturesTarget) engineLocation.value = settled(requested)
        }
        coEvery { engine.cancelLoad() } returns BrowsingEngine.CancelResult.NavigationRestored(home)
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            workspace.navigate(downloadTarget)
            advanceUntilIdle()
            workspace.navigate(ExplorerNavigation.Back)
            advanceUntilIdle()
            workspace.ready().run {
                currentTarget shouldBe home
                historyIndex shouldBe 0
            }

            workspace.navigate(picturesTarget)
            advanceUntilIdle()
            workspace.navigate(ExplorerNavigation.Cancel)
            advanceUntilIdle()

            workspace.ready().run {
                navigationHistory[historyIndex] shouldBe currentTarget
                currentTarget shouldBe home
                historyIndex shouldBe 0
                navigationHistory shouldBe listOf(home, downloadTarget)
            }
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `an aborted first load leaves no history behind`() = runTest {
        coEvery { engine.cancelLoad() } returns BrowsingEngine.CancelResult.NothingToRestore(home)
        val workspace = startedOnHome()

        try {
            advanceUntilIdle()
            workspace.ready().navigationHistory shouldBe listOf(home)

            workspace.navigate(ExplorerNavigation.Cancel)
            advanceUntilIdle()
            workspace.ready().run {
                currentTarget shouldBe null
                navigationHistory shouldBe emptyList()
                historyIndex shouldBe 0
                canGoBack shouldBe false
            }

            // What the aborted dialog's retry does: exactly one entry, still nothing to go back to.
            workspace.navigate(home)
            advanceUntilIdle()
            workspace.ready().run {
                currentTarget shouldBe home
                navigationHistory shouldBe listOf(home)
                historyIndex shouldBe 0
                canGoBack shouldBe false
            }

            workspace.navigate(ExplorerNavigation.Cancel)
            advanceUntilIdle()
            workspace.ready().canGoBack shouldBe false

            // Dismissing goes back if it can - it cannot, so it lands on Home instead of returning
            // to the target that was just aborted.
            workspace.navigate(ExplorerNavigation.Back)
            advanceUntilIdle()
            workspace.ready().currentTarget shouldBe null
        } finally {
            workspace.release()
        }
    }
}

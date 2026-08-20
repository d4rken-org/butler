package eu.darken.butler.explorer.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a session save captures. [ExplorerWorkspace.createArguments] must describe ONE location:
 * the target the tab is on. The engine in [testExplorerWorkspace] never reports a loaded location,
 * so every case here runs with a navigation still in flight - the state a save can catch when the
 * user switches apps mid-navigation, or after a navigation failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerWorkspaceArgumentsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val download = LocalPath.build("/sdcard/Download")

    @Test
    fun `a directory in flight is persisted by its path`() = runTest {
        val workspace = testExplorerWorkspace(
            ExplorerArguments.Default(startPath = download),
            UnconfinedTestDispatcher(testScheduler),
        )

        val saved = workspace.createArguments() as ExplorerArguments.Default

        saved.startPath shouldBe download
        saved.startTarget shouldBe null
        workspace.release()
    }

    @Test
    fun `a parked target in flight is persisted by its target`() = runTest {
        val workspace = testExplorerWorkspace(
            ExplorerArguments.Default(startTarget = ExplorerStartTarget.TRASH),
            UnconfinedTestDispatcher(testScheduler),
        )

        val saved = workspace.createArguments() as ExplorerArguments.Default

        saved.startPath shouldBe null
        saved.startTarget shouldBe ExplorerStartTarget.TRASH
        workspace.release()
    }

    @Test
    fun `navigating away in flight persists the new location, never a mix of both`() = runTest {
        val workspace = testExplorerWorkspace(
            ExplorerArguments.Default(startPath = download),
            UnconfinedTestDispatcher(testScheduler),
        )

        workspace.navigate(ExplorerNavigation.Target.Home)
        val saved = workspace.createArguments() as ExplorerArguments.Default

        saved.startTarget shouldBe ExplorerStartTarget.HOME
        saved.startPath shouldBe null
        workspace.release()
    }

    @Test
    fun `a save before any navigation keeps the original arguments`() = runTest {
        // Unadvanced scheduler: the workspace has not processed its initial navigation yet
        val arguments = ExplorerArguments.Default(startPath = download)
        val workspace = testExplorerWorkspace(arguments)

        workspace.createArguments() shouldBe arguments
    }

    /**
     * The reveal hint is a creation-time instruction, not tab state. Saving it would replay the
     * highlight on every restore of that tab, long after the file was shown once.
     */
    @Test
    fun `a settled tab never saves its reveal hint`() = runTest {
        val workspace = testExplorerWorkspace(
            ExplorerArguments.Default(startPath = download, revealPath = download.child("backup.zip")),
            UnconfinedTestDispatcher(testScheduler),
        )

        val saved = workspace.createArguments() as ExplorerArguments.Default

        saved.startPath shouldBe download
        saved.revealPath shouldBe null
        workspace.release()
    }

    @Test
    fun `a tab still initializing never saves its reveal hint either`() = runTest {
        // Unadvanced scheduler: the creation arguments are what a save catches here, verbatim -
        // which is exactly the window where the hint would slip through.
        val workspace = testExplorerWorkspace(
            ExplorerArguments.Default(startPath = download, revealPath = download.child("backup.zip")),
        )

        val saved = workspace.createArguments() as ExplorerArguments.Default

        saved.startPath shouldBe download
        saved.revealPath shouldBe null
    }

    @Test
    fun `the reveal hint is handed out exactly once`() = runTest {
        val file = download.child("backup.zip")
        val workspace = testExplorerWorkspace(
            ExplorerArguments.Default(startPath = download, revealPath = file),
        )

        workspace.consumeRevealHint() shouldBe ExplorerWorkspace.RevealHint(location = download, path = file)
        // A rebuilt page ViewModel asks again; the highlight must not come back with it.
        workspace.consumeRevealHint() shouldBe null
    }

    @Test
    fun `a tab without a reveal hint has none to hand out`() = runTest {
        testExplorerWorkspace(ExplorerArguments.Default(startPath = download))
            .consumeRevealHint() shouldBe null
        // A hint without a location has nothing to wait for.
        testExplorerWorkspace(ExplorerArguments.Default(revealPath = download.child("backup.zip")))
            .consumeRevealHint() shouldBe null
    }

    @Test
    fun `the saved arguments keep naming the tab the same way`() = runTest {
        val workspace = testExplorerWorkspace(
            ExplorerArguments.Default(startTarget = ExplorerStartTarget.DEVICE),
            UnconfinedTestDispatcher(testScheduler),
        )

        val saved = workspace.createArguments()

        val restored = deriveExplorerDisplay(saved)!!
        restored.title!!.get(context) shouldBe workspace.info.value.title.get(context)
        restored.subtitle?.get(context) shouldBe workspace.info.value.subtitle?.get(context)
        workspace.release()
    }
}

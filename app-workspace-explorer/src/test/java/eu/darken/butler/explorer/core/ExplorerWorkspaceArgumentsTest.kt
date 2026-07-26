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

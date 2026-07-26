package eu.darken.butler.explorer.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.label
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The live workspace must seed its [Workspace.Info] from the same derivation the paused stand-in
 * uses, otherwise a restored tab renames itself the moment it is resumed.
 *
 * For the seed assertions the workspace scope runs on an unadvanced [StandardTestDispatcher], so
 * `info.value` is still the explicit seed and not the first emission of the eagerly shared upstream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerWorkspaceSeedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun assertSeedMatchesDerivation(arguments: ExplorerArguments) {
        val derived = deriveExplorerDisplay(arguments)
        val seed = testExplorerWorkspace(arguments).info.value

        seed.title.get(context) shouldBe (derived?.title?.get(context) ?: Workspace.Type.EXPLORER.label.get(context))
        seed.subtitle?.get(context) shouldBe derived?.subtitle?.get(context)
    }

    @Test
    fun `directory tab seeds the derived path`() {
        assertSeedMatchesDerivation(ExplorerArguments.Default(startPath = LocalPath.build("/sdcard/Download")))
    }

    @Test
    fun `parked tab seeds the derived navigation label`() {
        assertSeedMatchesDerivation(ExplorerArguments.Default(startTarget = ExplorerStartTarget.TRASH))
    }

    @Test
    fun `tab without identity falls back to the type label`() {
        assertSeedMatchesDerivation(ExplorerArguments.Default())
    }

    /**
     * Resuming must not rename the tab: the restored navigation target is the one the paused
     * title was derived from, so the live title lands on the same label.
     */
    private suspend fun TestScope.assertResumingKeepsTheIdentity(arguments: ExplorerArguments) {
        val derived = deriveExplorerDisplay(arguments)
        // Unconfined: the initial navigation request has been processed by the time this returns
        val workspace = testExplorerWorkspace(arguments, UnconfinedTestDispatcher(testScheduler))

        try {
            workspace.info.value.title.get(context) shouldBe derived!!.title!!.get(context)
            workspace.info.value.subtitle?.get(context) shouldBe derived.subtitle?.get(context)
        } finally {
            workspace.release()
        }
    }

    @Test
    fun `a parked tab keeps its name after resuming`() = runTest {
        assertResumingKeepsTheIdentity(ExplorerArguments.Default(startTarget = ExplorerStartTarget.TRASH))
    }

    @Test
    fun `a parked device tab keeps its name after resuming`() = runTest {
        assertResumingKeepsTheIdentity(ExplorerArguments.Default(startTarget = ExplorerStartTarget.DEVICE))
    }

    @Test
    fun `a directory tab keeps its path after resuming`() = runTest {
        assertResumingKeepsTheIdentity(
            ExplorerArguments.Default(startPath = LocalPath.build("/sdcard/Download")),
        )
    }

    /**
     * Navigating away from a location that describes itself must take that description with it -
     * and a save at that moment must persist the identity the tab is actually showing.
     */
    @Test
    fun `navigating away from Trash drops its description`() = runTest {
        val workspace = testExplorerWorkspace(
            ExplorerArguments.Default(startTarget = ExplorerStartTarget.TRASH),
            UnconfinedTestDispatcher(testScheduler),
        )

        try {
            // Where it starts: Trash carries a second line
            workspace.info.value.title.get(context) shouldBe
                context.getString(R.string.explorer_navigation_trash)
            workspace.info.value.subtitle!!.get(context) shouldBe
                context.getString(R.string.explorer_navigation_trash_desc)
            workspace.assertRestoreMatchesLive()

            workspace.navigate(ExplorerNavigation.Target.Home)
            workspace.info.value.title.get(context) shouldBe
                context.getString(R.string.explorer_navigation_home)
            workspace.info.value.subtitle shouldBe null
            workspace.assertRestoreMatchesLive()

            workspace.navigate(ExplorerNavigation.Target.Device)
            workspace.info.value.title.get(context) shouldBe
                context.getString(R.string.explorer_navigation_device)
            workspace.info.value.subtitle shouldBe null
            workspace.assertRestoreMatchesLive()

            workspace.navigate(ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Download")))
            workspace.info.value.title.get(context) shouldBe "/sdcard/Download"
            workspace.info.value.subtitle shouldBe null
            workspace.assertRestoreMatchesLive()
        } finally {
            workspace.release()
        }
    }

    /** A restore of this tab, right now, must show what the tab is showing. */
    private suspend fun ExplorerWorkspace.assertRestoreMatchesLive() {
        val live = info.value
        val restored = deriveExplorerDisplay(createArguments())

        restored?.title?.get(context) shouldBe live.title.get(context)
        restored?.subtitle?.get(context) shouldBe live.subtitle?.get(context)
    }
}

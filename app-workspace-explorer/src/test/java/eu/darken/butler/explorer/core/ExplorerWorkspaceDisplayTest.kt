package eu.darken.butler.explorer.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorerWorkspaceDisplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `home target is named after its navigation label`() {
        val display = deriveExplorerDisplay(ExplorerArguments.Default(startTarget = ExplorerStartTarget.HOME))

        display!!.title!!.get(context) shouldBe context.getString(R.string.explorer_navigation_home)
        display.subtitle shouldBe null
    }

    @Test
    fun `device target is named after its navigation label`() {
        val display = deriveExplorerDisplay(ExplorerArguments.Default(startTarget = ExplorerStartTarget.DEVICE))

        display!!.title!!.get(context) shouldBe context.getString(R.string.explorer_navigation_device)
        display.subtitle shouldBe null
    }

    @Test
    fun `trash target is named after its navigation label`() {
        val display = deriveExplorerDisplay(ExplorerArguments.Default(startTarget = ExplorerStartTarget.TRASH))

        display!!.title!!.get(context) shouldBe context.getString(R.string.explorer_navigation_trash)
    }

    @Test
    fun `a target that describes itself keeps that description while dormant`() {
        // Trash publishes a second line once loaded; the dormant tab must show the same one
        val display = deriveExplorerDisplay(ExplorerArguments.Default(startTarget = ExplorerStartTarget.TRASH))

        display!!.subtitle!!.get(context) shouldBe context.getString(R.string.explorer_navigation_trash_desc)
    }

    @Test
    fun `a directory is named by its full path`() {
        val display = deriveExplorerDisplay(
            ExplorerArguments.Default(startPath = LocalPath.build("/storage/emulated/0/Download")),
        )

        display!!.title!!.get(context) shouldBe "/storage/emulated/0/Download"
        display.subtitle shouldBe null
    }

    @Test
    fun `an explicit path wins over a parked target, like navigation does`() {
        val arguments = ExplorerArguments.Default(
            startPath = LocalPath.build("/storage/emulated/0/Download"),
            startTarget = ExplorerStartTarget.HOME,
        )

        val display = deriveExplorerDisplay(arguments)

        // The tab must be named after the location hydration will actually open
        display!!.title!!.get(context) shouldBe "/storage/emulated/0/Download"
        display.title!!.get(context) shouldBe explorerStartTarget(
            startPath = arguments.startPath,
            startTarget = arguments.startTarget,
            defaultStartLocation = null,
        ).label.get(context)
    }

    @Test
    fun `the derived identity is always that of the location that will open`() {
        val cases = listOf(
            ExplorerArguments.Default(startPath = LocalPath.build("/sdcard/DCIM")),
            ExplorerArguments.Default(startTarget = ExplorerStartTarget.HOME),
            ExplorerArguments.Default(startTarget = ExplorerStartTarget.DEVICE),
            ExplorerArguments.Default(startTarget = ExplorerStartTarget.TRASH),
            ExplorerArguments.Default(
                startPath = LocalPath.build("/sdcard/DCIM"),
                startTarget = ExplorerStartTarget.TRASH,
            ),
        )

        cases.forEach { arguments ->
            // Both lines, from the same target the live info flow publishes
            val opened = explorerStartTarget(arguments.startPath, arguments.startTarget, null)
            val derived = deriveExplorerDisplay(arguments)!!

            derived.title!!.get(context) shouldBe opened.label.get(context)
            derived.subtitle?.get(context) shouldBe opened.description?.get(context)
        }
    }

    @Test
    fun `arguments without a target or path carry no identity`() {
        deriveExplorerDisplay(ExplorerArguments.Default()) shouldBe null
    }

    @Test
    fun `picker arguments carry no identity`() {
        val display = deriveExplorerDisplay(
            ExplorerArguments.Picker(
                startPath = LocalPath.build("/sdcard"),
                selection = PickerConfig.Selection.DirectorySingle,
                callerWorkspaceId = Workspace.Id(),
            ),
        )

        display shouldBe null
    }
}

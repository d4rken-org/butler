package eu.darken.butler.apps.core.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider

/**
 * The live workspace must seed its [Workspace.Info] from the same derivation the paused stand-in
 * uses; enriching the package name to the app label afterwards is expected.
 *
 * The workspace scope runs on an unadvanced [StandardTestDispatcher], so `info.value` is still the
 * explicit seed and not the first emission of the eagerly shared upstream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDetailsWorkspaceSeedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the seed matches the derivation`() {
        val arguments = AppDetailsArguments(packageName = "eu.darken.butler")
        val workspace = AppDetailsWorkspace(
            id = Workspace.Id(),
            creationArguments = arguments,
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher()),
            pkgRepo = mockk(relaxed = true),
            rootManager = mockk(relaxed = true),
            adbManager = mockk(relaxed = true),
            workspaceRemote = mockk(relaxed = true),
        )
        val derived = deriveAppDetailsDisplay(arguments)

        workspace.info.value.title.get(context) shouldBe derived.title!!.get(context)
        workspace.info.value.subtitle?.get(context) shouldBe derived.subtitle?.get(context)
    }
}

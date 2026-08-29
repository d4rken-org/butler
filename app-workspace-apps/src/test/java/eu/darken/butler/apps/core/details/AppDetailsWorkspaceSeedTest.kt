package eu.darken.butler.apps.core.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
        val arguments = AppDetailsArguments(
            installId = InstallId(Pkg.Id("eu.darken.butler"), UserHandle2(0)),
            appLabel = "Butler",
        )
        val workspace = AppDetailsWorkspace(
            id = Workspace.Id(),
            creationArguments = arguments,
            context = context,
            dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher()),
            pkgRepo = mockk(relaxed = true),
            pkgOps = mockk(relaxed = true),
            apkArchiveParser = mockk(relaxed = true),
            appSizeCache = mockk(relaxed = true) {
                every { snapshot } returns MutableStateFlow(AppSizeCache.Snapshot())
                every { isAvailable } returns MutableStateFlow(false)
            },
            // Explicit, not relaxed: an ABSENT answer would silently drop rows.
            gatewaySwitch = mockk { coEvery { existsStrict(any()) } returns Existence.PRESENT },
            pathPermissionCheck = mockk { every { monitor(any<APath<*>>()) } returns flowOf(PathRequirements()) },
            rootManager = mockk(relaxed = true),
            adbManager = mockk(relaxed = true),
            workspaceRemote = mockk(relaxed = true),
        )
        val derived = deriveAppDetailsDisplay(arguments)

        workspace.info.value.title.get(context) shouldBe derived.title!!.get(context)
        workspace.info.value.subtitle?.get(context) shouldBe derived.subtitle?.get(context)
    }
}

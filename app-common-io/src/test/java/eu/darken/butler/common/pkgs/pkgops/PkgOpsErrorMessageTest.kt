package eu.darken.butler.common.pkgs.pkgops

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.user.UserHandle2
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2

/**
 * The shell's reason for refusing an uninstall or a data wipe only reaches the user if it survives
 * into the [PkgOpsException]'s own message: what renders an error reads the top-level message and
 * nothing from the cause chain.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class PkgOpsErrorMessageTest : BaseTest() {

    private val installId = InstallId(Pkg.Id("com.example.app"), UserHandle2(0))

    private lateinit var rootManager: RootManager
    private lateinit var adbManager: AdbManager
    private lateinit var pkgOps: PkgOps

    @Before
    fun setup() {
        rootManager = mockk()
        adbManager = mockk()
        every { rootManager.useRoot } returns flowOf(false)
        every { adbManager.useAdb } returns flowOf(false)
        every { rootManager.serviceClient } returns mockk(relaxed = true)
        every { adbManager.serviceClient } returns mockk(relaxed = true)

        pkgOps = PkgOps(
            appScope = TestScope(),
            dispatcherProvider = TestDispatcherProvider(),
            context = mockk(relaxed = true),
            ipcFunnel = mockk(relaxed = true),
            rootManager = rootManager,
            adbManager = adbManager,
            usageStatsManager = mockk(relaxed = true),
            storageStatsManager = mockk(relaxed = true),
            userManager2 = mockk(relaxed = true),
            localFileMaterializer = mockk(relaxed = true),
        )
    }

    /** Same simulation the gateway tests use: the client's resource lease is what fails. */
    private fun failElevatedAccessWith(message: String) {
        every { rootManager.useRoot } returns flowOf(true)
        coEvery { rootManager.serviceClient.get() } throws IllegalStateException(message)
    }

    @Test
    fun `a refused uninstall carries the reason`() = runTest2 {
        failElevatedAccessWith("`pm uninstall --user 0 com.example.app` failed: DELETE_FAILED_INTERNAL_ERROR")

        val thrown = shouldThrow<PkgOpsException> { pkgOps.uninstall(installId) }

        thrown.message!! shouldContain "DELETE_FAILED_INTERNAL_ERROR"
    }

    @Test
    fun `a refused clear data carries the reason`() = runTest2 {
        failElevatedAccessWith("`pm clear --user 0 com.example.app` failed: Permission Denial")

        val thrown = shouldThrow<PkgOpsException> { pkgOps.clearData(installId) }

        thrown.message!! shouldContain "Permission Denial"
    }

    @Test
    fun `missing elevated access stays a bare message for the cause-walk`() = runTest2 {
        val thrown = shouldThrow<PkgOpsException> { pkgOps.uninstall(installId) }

        thrown.message!! shouldNotContain "unavailable"
        // The fallback to Android's own uninstall dialog hinges on finding this in the chain.
        generateSequence<Throwable>(thrown) { it.cause }
            .filterIsInstance<ElevatedAccessUnavailableException>()
            .firstOrNull()
            .shouldNotBeNull()
    }

    @Test
    fun `missing elevated access stays a bare message for clear data too`() = runTest2 {
        val thrown = shouldThrow<PkgOpsException> { pkgOps.clearData(installId) }

        thrown.message!! shouldNotContain "unavailable"
    }
}

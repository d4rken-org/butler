package eu.darken.butler.apps.core.details.components

import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.pkgs.features.InstallDetails
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.root.RootManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ComponentToggleAvailabilityTest : BaseTest() {

    /** [AppInfo.isEnabled] resolves through the `Pkg.isEnabled` extension, which needs both types. */
    private interface TestInstall : Installed, InstallDetails

    private val ownPackage = "eu.darken.butler"

    private fun appInfo(
        pkg: String = "com.example.app",
        enabled: Boolean = true,
    ) = AppInfo(
        install = mockk<TestInstall> {
            every { packageName } returns pkg
            every { isEnabled } returns enabled
        },
    )

    private fun CoroutineScope.availability(
        appInfo: Flow<AppInfo?>,
        root: Flow<Boolean>,
        adb: Flow<Boolean>,
    ) = ComponentToggleAvailability(
        scope = this,
        appInfo = appInfo,
        rootManager = mockk<RootManager> { every { useRoot } returns root },
        adbManager = mockk<AdbManager> { every { useAdb } returns adb },
        ownPackageName = ownPackage,
    )

    @Test
    fun `nothing is claimed before the sources emit`() = runTest {
        val availability = backgroundScope.availability(
            appInfo = MutableSharedFlow(),
            root = MutableStateFlow(false),
            adb = MutableStateFlow(false),
        )
        runCurrent()

        availability.state.value shouldBe null
    }

    @Test
    fun `root alone makes it available`() = runTest {
        val availability = backgroundScope.availability(
            appInfo = MutableStateFlow(appInfo()),
            root = MutableStateFlow(true),
            adb = MutableStateFlow(false),
        )
        runCurrent()

        availability.state.value shouldBe ComponentToggleState.AVAILABLE
    }

    @Test
    fun `adb alone makes it available`() = runTest {
        val availability = backgroundScope.availability(
            appInfo = MutableStateFlow(appInfo()),
            root = MutableStateFlow(false),
            adb = MutableStateFlow(true),
        )
        runCurrent()

        availability.state.value shouldBe ComponentToggleState.AVAILABLE
    }

    @Test
    fun `without elevated access it needs setup`() = runTest {
        val availability = backgroundScope.availability(
            appInfo = MutableStateFlow(appInfo()),
            root = MutableStateFlow(false),
            adb = MutableStateFlow(false),
        )
        runCurrent()

        availability.state.value shouldBe ComponentToggleState.NEEDS_SETUP
    }

    @Test
    fun `a missing app is unsupported`() = runTest {
        val availability = backgroundScope.availability(
            appInfo = MutableStateFlow(null),
            root = MutableStateFlow(true),
            adb = MutableStateFlow(true),
        )
        runCurrent()

        availability.state.value shouldBe ComponentToggleState.UNSUPPORTED
    }

    @Test
    fun `butler itself is unsupported`() = runTest {
        val availability = backgroundScope.availability(
            appInfo = MutableStateFlow(appInfo(pkg = ownPackage)),
            root = MutableStateFlow(true),
            adb = MutableStateFlow(true),
        )
        runCurrent()

        availability.state.value shouldBe ComponentToggleState.UNSUPPORTED
    }

    /** Every component of a disabled app already resolves DISABLED, so a toggle changes nothing. */
    @Test
    fun `a disabled application is unsupported even with root`() = runTest {
        val availability = backgroundScope.availability(
            appInfo = MutableStateFlow(appInfo(enabled = false)),
            root = MutableStateFlow(true),
            adb = MutableStateFlow(false),
        )
        runCurrent()

        availability.state.value shouldBe ComponentToggleState.UNSUPPORTED
    }

    @Test
    fun `losing root re-emits`() = runTest {
        val root = MutableStateFlow(true)
        val availability = backgroundScope.availability(
            appInfo = MutableStateFlow(appInfo()),
            root = root,
            adb = MutableStateFlow(false),
        )
        runCurrent()
        availability.state.value shouldBe ComponentToggleState.AVAILABLE

        root.value = false
        runCurrent()

        availability.state.value shouldBe ComponentToggleState.NEEDS_SETUP
    }
}

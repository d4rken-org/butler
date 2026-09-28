package eu.darken.butler.setup.core

import android.content.Context
import eu.darken.butler.setup.core.shizuku.AdbManagerInstallGuide
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SetupManagerTest : BaseTest() {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private val shizukuModule: ShizukuSetupModule = mockk<ShizukuSetupModule>().also {
        every { it.type } returns SetupModule.Type.SHIZUKU
        every { it.state } returns emptyFlow()
        coEvery { it.grantAccess() } just Runs
    }

    @AfterEach
    fun teardown() {
        scope.cancel()
    }

    private fun manager() = SetupManager(
        context = mockk<Context>(relaxed = true),
        appScope = scope,
        adbManagerInstallGuide = mockk<AdbManagerInstallGuide>(),
        setupModules = setOf(shizukuModule),
    )

    @Test fun `a permission request for ADB access asks the manager`() {
        val result = runBlocking { manager().executeAction(SetupModule.Type.SHIZUKU, SetupAction.RequestPermission) }

        coVerify(exactly = 1) { shizukuModule.grantAccess() }
        result shouldBe null
    }
}

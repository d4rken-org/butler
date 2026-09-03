package eu.darken.butler.workspace.core.operations

import android.content.Context
import android.content.Intent
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.installer.AppInstallEvent
import eu.darken.butler.common.pkgs.installer.AppInstallFormat
import eu.darken.butler.common.pkgs.installer.AppInstallInspector
import eu.darken.butler.common.pkgs.installer.AppInstallPlan
import eu.darken.butler.common.pkgs.installer.AppInstaller
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2

class AppInstallLauncherTest : BaseTest() {

    private val source = LocalPath.build("/sdcard/Download/example.apk")

    private val plan = AppInstallPlan(
        source = source,
        format = AppInstallFormat.APK,
        pkgId = "com.example.app".toPkgId(),
        baseInfo = null,
        splits = listOf(
            AppInstallPlan.Split(entryPath = "example.apk", stagedName = "base.apk", size = 1024L),
        ),
        obbEntries = emptyList(),
        warnings = emptyList(),
    )

    private val origin = Operation.Metadata.Origin.Searcher(Workspace.Id())
    private val operationId = Operation.Id()

    private val context = mockk<Context>(relaxed = true)
    private val inspector = mockk<AppInstallInspector>()
    private val installer = mockk<AppInstaller>()
    private val operationsManager = mockk<OperationsManager>()
    private val operationFactory = mockk<AppInstallOperation.Factory>()

    private fun create(
        hasElevation: Boolean = true,
        canUseSystemInstaller: Boolean = false,
    ): AppInstallLauncher {
        coEvery { inspector.inspect(source) } returns plan
        coEvery { installer.hasElevation() } returns hasElevation
        every { installer.canUseSystemInstaller() } returns canUseSystemInstaller
        every { installer.unknownSourcesSettings() } returns mockk<Intent>(relaxed = true)
        every { operationFactory.create(any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { operationsManager.submit(any()) } returns operationId
        every { operationsManager.completedOperations } returns MutableSharedFlow()
        return AppInstallLauncher(
            context = context,
            appInstallInspector = inspector,
            appInstaller = installer,
            operationsManager = operationsManager,
            appInstallOperationFactory = operationFactory,
        )
    }

    @Test
    fun `a container that cannot be inspected never becomes an operation`() = runTest2 {
        val launcher = create()
        coEvery { inspector.inspect(source) } throws IllegalStateException("Unreadable")

        shouldThrow<IllegalStateException> {
            launcher.launch(source, origin, backgroundScope) {}
        }

        coVerify(exactly = 0) { operationsManager.submit(any()) }
    }

    @Test
    fun `elevated access installs without asking about unknown sources`() = runTest2 {
        val launcher = create(hasElevation = true, canUseSystemInstaller = false)

        val result = launcher.launch(source, origin, backgroundScope) {}

        result shouldBe AppInstallLauncher.Result.Submitted(operationId)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `an authorized install source installs without elevated access`() = runTest2 {
        val launcher = create(hasElevation = false, canUseSystemInstaller = true)

        val result = launcher.launch(source, origin, backgroundScope) {}

        result shouldBe AppInstallLauncher.Result.Submitted(operationId)
        coVerify { operationsManager.submit(any()) }
    }

    /** Without either route the install would only fail later, so the settings page comes first. */
    @Test
    fun `an unauthorized install source is sent to the settings page instead`() = runTest2 {
        val launcher = create(hasElevation = false, canUseSystemInstaller = false)

        val result = launcher.launch(source, origin, backgroundScope) {}

        result shouldBe AppInstallLauncher.Result.UnknownSourcesRequired
        verify { context.startActivity(any()) }
        coVerify(exactly = 0) { operationsManager.submit(any()) }
    }

    @Test
    fun `the inspected plan and the calling workspace reach the operation`() = runTest2 {
        val launcher = create()
        val planSlot = slot<AppInstallPlan>()
        val originSlot = slot<Operation.Metadata.Origin>()
        every {
            operationFactory.create(capture(originSlot), capture(planSlot), any())
        } returns mockk(relaxed = true)

        launcher.launch(source, origin, backgroundScope) {}

        planSlot.captured shouldBe plan
        originSlot.captured shouldBe origin
    }

    /**
     * The operation may report a failed expansion file before the collector is running, which is
     * the one window in which the toast used to be lost.
     */
    @Test
    fun `a failed expansion file reported before the collector starts still reaches the caller`() = runTest2 {
        val launcher = create()
        val reasons = mutableListOf<String>()
        every { operationFactory.create(any(), any(), any()) } answers {
            thirdArg<MutableSharedFlow<AppInstallEvent>>().tryEmit(AppInstallEvent.ObbFailed("No space left"))
            mockk(relaxed = true)
        }

        launcher.launch(source, origin, backgroundScope) { reasons.add(it) }
        advanceUntilIdle()

        reasons shouldBe listOf("No space left")
    }
}

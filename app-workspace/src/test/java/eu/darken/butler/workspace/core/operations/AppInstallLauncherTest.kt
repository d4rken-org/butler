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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import kotlin.coroutines.CoroutineContext

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
     * The operation may report a failed expansion file while it is still being submitted, which is
     * the one window in which the toast used to be lost.
     */
    @Test
    fun `a failed expansion file reported during submission still reaches the caller`() = runTest2 {
        val launcher = create()
        val reasons = mutableListOf<String>()
        every { operationFactory.create(any(), any(), any()) } answers {
            thirdArg<SendChannel<AppInstallEvent>>().trySend(AppInstallEvent.ObbFailed("No space left"))
            mockk(relaxed = true)
        }

        val dispatcher = ReorderingDispatcher()
        val collectorScope = CoroutineScope(dispatcher + Job())

        launcher.launch(source, origin, collectorScope) { reasons.add(it) }
        dispatcher.runQueued()

        reasons shouldBe listOf("No space left")
        collectorScope.cancel()
    }

    /**
     * The normal case for a real install, driven through the real lifecycle: the expansion file
     * fails while the install runs, the install finishes right behind it, and the operation being
     * discarded is what closes the channel. Nothing stands in for the operation here, so a reason
     * lost at completion and a listener that outlives its install both surface as a failure.
     */
    @Test
    fun `a failed expansion file survives the install completing right after it`() = runTest2 {
        val launcher = create()
        every { installer.install(any(), any()) } returns flowOf(
            AppInstallEvent.ObbFailed("No space left"),
            AppInstallEvent.Success(pkgId = plan.pkgId, viaMode = AppInstaller.Mode.ROOT, obbPlaced = false),
        )
        every { operationFactory.create(any(), any(), any()) } answers {
            AppInstallOperation(
                installOrigin = firstArg<Operation.Metadata.Origin>(),
                plan = secondArg<AppInstallPlan>(),
                events = thirdArg<SendChannel<AppInstallEvent>>(),
                appInstaller = installer,
            )
        }
        // Not backgroundScope: advanceUntilIdle() ignores background work, so the operation would
        // never run at all and the test would pass an empty channel off as a delivered event.
        val operationScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        coEvery { operationsManager.submit(any()) } coAnswers {
            ManagedOperation(operationId, firstArg<Operation>(), operationScope).start()
            operationId
        }

        val dispatcher = ReorderingDispatcher()
        val collectorScope = CoroutineScope(dispatcher + Job())
        val reasons = mutableListOf<String>()

        launcher.launch(source, origin, collectorScope) { reasons.add(it) }
        val collector = collectorScope.coroutineContext.job.children.single()

        advanceUntilIdle()
        dispatcher.runQueued()

        reasons shouldBe listOf("No space left")
        collector.isCompleted shouldBe true
        collectorScope.cancel()
        operationScope.cancel()
    }

    /**
     * The operation closes the channel when it is discarded, and that is the only thing that ends
     * the collector: an event the channel still holds has to reach the caller anyway, and the
     * collector has to be gone afterwards so no listener outlives its install.
     */
    @Test
    fun `closing the events channel delivers what it still holds and then ends the collector`() = runTest2 {
        val launcher = create()
        val events = slot<SendChannel<AppInstallEvent>>()
        every { operationFactory.create(any(), any(), capture(events)) } returns mockk(relaxed = true)

        val dispatcher = ReorderingDispatcher()
        val collectorScope = CoroutineScope(dispatcher + Job())
        val reasons = mutableListOf<String>()

        launcher.launch(source, origin, collectorScope) { reasons.add(it) }
        val collector = collectorScope.coroutineContext.job.children.single()

        events.captured.send(AppInstallEvent.ObbFailed("No space left"))
        events.captured.close()
        dispatcher.runQueued()

        reasons shouldBe listOf("No space left")
        collector.isCompleted shouldBe true
        collectorScope.cancel()
    }

    /**
     * Stands in for a multi-threaded dispatcher: it queues resumptions instead of running them, so a
     * test can pick when ready coroutines run.
     */
    private class ReorderingDispatcher : CoroutineDispatcher() {

        private val tasks = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(tasks) { tasks.add(block) }
        }

        fun runQueued() {
            while (true) {
                val next = synchronized(tasks) { tasks.firstOrNull()?.also { tasks.removeAt(0) } } ?: return
                next.run()
            }
        }
    }
}

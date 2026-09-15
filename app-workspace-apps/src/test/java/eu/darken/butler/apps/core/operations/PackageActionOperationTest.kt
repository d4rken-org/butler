package eu.darken.butler.apps.core.operations

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.pkgs.pkgops.PkgOpsException
import eu.darken.butler.common.pkgs.uninstaller.AppUninstallConfirmationIssue
import eu.darken.butler.common.pkgs.uninstaller.SystemUninstaller
import eu.darken.butler.common.pkgs.uninstaller.SystemUninstallException
import eu.darken.butler.common.pkgs.uninstaller.UninstallDeclinedException
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.IOException
import kotlin.time.Clock

/**
 * One operation serves every package action, so the interesting behaviour is per target: what a
 * mixed batch reports, what a declined system dialog means, and what still gets refreshed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackageActionOperationTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val pkgOps = mockk<PkgOps>(relaxed = true)
    private val pkgRepo = mockk<PkgRepo>(relaxed = true)
    private val appSizeCache = mockk<AppSizeCache>(relaxed = true)
    private val systemUninstaller = mockk<SystemUninstaller>(relaxed = true)

    private val workspaceId = Workspace.Id()
    private val origin = Operation.Metadata.Origin.Apps(workspaceId)

    private fun target(name: String, label: String = name) = PackageCommand.Target(
        installId = InstallId(Pkg.Id(name), UserHandle2(0)),
        label = label.toCaString(),
    )

    private fun operation(command: PackageCommand) = PackageActionOperation(
        actionOrigin = origin,
        command = command,
        context = context,
        pkgOps = pkgOps,
        pkgRepo = pkgRepo,
        appSizeCache = appSizeCache,
        systemUninstaller = systemUninstaller,
    )

    private suspend fun run(command: PackageCommand): List<Operation.State> = operation(command)
        .perform(Operation.Context(id = Operation.Id(), startedAt = Clock.System.now()))
        .toList()

    private fun List<Operation.State>.completed(): Operation.State.Completed =
        last().shouldBeInstanceOf<Operation.State.Completed>()

    private fun Operation.State.Completed.packages(): Operation.Report.Packages =
        report.shouldBeInstanceOf<Operation.Report.Packages>()

    @Test
    fun `metadata declares a non-cancellable operation that keeps its tab`() {
        val metadata = operation(PackageCommand.Uninstall(listOf(target("a")), viaSystemDialog = false)).metadata

        metadata.isCancellable shouldBe false
        metadata.closePolicy shouldBe Operation.Metadata.ClosePolicy.REQUIRE_ORIGIN
        metadata.kind shouldBe Operation.Metadata.Kind.UNINSTALL
        metadata.origin shouldBe origin
    }

    @Test
    fun `a batch with one failure completes partially`() = runTest2 {
        coEvery { pkgOps.changePackageState(Pkg.Id("b"), any(), any()) } throws IOException("nope")

        val completed = run(
            PackageCommand.Disable(
                listOf(target("a", label = "Alpha"), target("b", label = "Beta"), target("c", label = "Gamma"))
            )
        ).completed()

        completed.error shouldBe null
        val report = completed.packages()
        report.outcomes.map { it.status } shouldContainExactly listOf(
            Operation.Report.Packages.Outcome.Status.DONE,
            Operation.Report.Packages.Outcome.Status.FAILED,
            Operation.Report.Packages.Outcome.Status.DONE,
        )
        report.outcomes.map { it.label.get(context) } shouldContainExactly listOf("Alpha", "Beta", "Gamma")
        report.outcomes.map { it.packageName } shouldContainExactly listOf("a", "b", "c")
        report.partialErrorCount shouldBe 1
        coVerify(exactly = 1) { pkgRepo.refresh() }
    }

    @Test
    fun `a batch where every target fails is a failure`() = runTest2 {
        val first = IOException("first")
        coEvery { pkgOps.changePackageState(Pkg.Id("a"), any(), any()) } throws first
        coEvery { pkgOps.changePackageState(Pkg.Id("b"), any(), any()) } throws IOException("second")

        val completed = run(PackageCommand.Enable(listOf(target("a"), target("b")))).completed()

        completed.error shouldBe first
        completed.packages().partialErrorCount shouldBe 2
    }

    @Test
    fun `a single declined system uninstall ends cancelled and keeps its receipt`() = runTest2 {
        coEvery { systemUninstaller.uninstall(any(), any(), any()) } throws UninstallDeclinedException()

        val command = PackageCommand.Uninstall(listOf(target("a")), viaSystemDialog = true)
        val completed = run(command).completed()

        completed.error.shouldBeInstanceOf<CancellationException>()
        completed.packages().outcomes.map { it.status } shouldContainExactly listOf(
            Operation.Report.Packages.Outcome.Status.DECLINED,
        )
        completed.summary.get(context) shouldBe "Removal of a declined"

        // Emitted, not thrown: the framework's cancellation path would drop the report.
        val manager = OperationsManager(TestDispatcherProvider())
        val managed = manager.submitManaged(operation(command))
        val terminal = managed.state.first { it is Operation.State.Completed } as Operation.State.Completed
        terminal.report.shouldBeInstanceOf<Operation.Report.Packages>().outcomes.single()
            .status shouldBe Operation.Report.Packages.Outcome.Status.DECLINED
    }

    @Test
    fun `a system uninstall refused for another user is a failed target`() = runTest2 {
        coEvery {
            systemUninstaller.uninstall(any(), any(), any())
        } throws SystemUninstallException("Removing this app for another user needs root or ADB access")

        val completed = run(
            PackageCommand.Uninstall(listOf(target("a")), viaSystemDialog = true)
        ).completed()

        completed.packages().outcomes.single().status shouldBe Operation.Report.Packages.Outcome.Status.FAILED
        completed.error.shouldBeInstanceOf<SystemUninstallException>()
        completed.summary.get(context) shouldBe "Could not complete for a"
    }

    @Test
    fun `a declined target in a batch is neither an error nor a stop`() = runTest2 {
        coEvery {
            systemUninstaller.uninstall(match { it.pkgId == Pkg.Id("a") }, any(), any())
        } throws UninstallDeclinedException()

        val completed = run(
            PackageCommand.Uninstall(listOf(target("a"), target("b")), viaSystemDialog = true)
        ).completed()

        completed.error shouldBe null
        val report = completed.packages()
        report.partialErrorCount shouldBe 0
        report.outcomes.map { it.status } shouldContainExactly listOf(
            Operation.Report.Packages.Outcome.Status.DECLINED,
            Operation.Report.Packages.Outcome.Status.DONE,
        )
    }

    @Test
    fun `an elevated uninstall without access falls back to the system dialog`() = runTest2 {
        coEvery { pkgOps.uninstall(any(), any()) } throws PkgOpsException(
            "no elevation",
            ElevatedAccessUnavailableException(),
        )

        val completed = run(
            PackageCommand.Uninstall(listOf(target("a")), viaSystemDialog = false)
        ).completed()

        coVerify(exactly = 1) { systemUninstaller.uninstall(any(), any(), any()) }
        completed.packages().outcomes.single().status shouldBe Operation.Report.Packages.Outcome.Status.DONE
    }

    @Test
    fun `a system confirmation surfaces as a waiting state`() = runTest2 {
        val issue = AppUninstallConfirmationIssue(label = "a", confirmIntent = Intent())
        coEvery { systemUninstaller.uninstall(any(), any(), any()) } coAnswers {
            thirdArg<suspend (AppUninstallConfirmationIssue) -> Unit>().invoke(issue)
        }

        val states = run(PackageCommand.Uninstall(listOf(target("a")), viaSystemDialog = true))

        val waitingIndex = states.indexOfFirst { it is Operation.State.Waiting }
        (states[waitingIndex] as Operation.State.Waiting).issue shouldBe issue
        states.drop(waitingIndex + 1).first().shouldBeInstanceOf<Operation.State.Active>()
        states.completed().error shouldBe null
    }

    @Test
    fun `clearing data invalidates the size cache and force stop refreshes nothing`() = runTest2 {
        val cleared = target("a")
        run(PackageCommand.ClearData(listOf(cleared))).completed().error shouldBe null

        coVerify(exactly = 1) { appSizeCache.invalidate(listOf(cleared.installId)) }
        coVerify(exactly = 1) { pkgRepo.refresh() }

        run(PackageCommand.ForceStop(listOf(target("b")))).completed().error shouldBe null

        coVerify(exactly = 1) { pkgRepo.refresh() }
        coVerify(exactly = 1) { appSizeCache.invalidate(any()) }
    }

    /** The command already happened; the source error reaches the screen through PkgRepo's state. */
    @Test
    fun `a failing refresh does not fail the run`() = runTest2 {
        coEvery { pkgRepo.refresh() } throws IOException("Package source unavailable")

        val completed = run(PackageCommand.Enable(listOf(target("a")))).completed()

        completed.error shouldBe null
        completed.packages().outcomes.single().status shouldBe Operation.Report.Packages.Outcome.Status.DONE
    }

    @Test
    fun `a component batch reports one outcome per component`() = runTest2 {
        val entries = listOf(
            ComponentEntry(
                kind = ComponentKind.ACTIVITY,
                packageName = "a",
                className = "a.MainActivity",
                isExported = true,
            ),
            ComponentEntry(
                kind = ComponentKind.SERVICE,
                packageName = "a",
                className = "a.SyncService",
                isExported = false,
            ),
        )
        val completed = run(
            PackageCommand.SetComponents(
                target = target("a", label = "Alpha"),
                entries = entries,
                enabled = false,
            )
        ).completed()

        completed.packages().outcomes.size shouldBe 2
        completed.packages().outcomes.first().label.get(context) shouldBe "Alpha · MainActivity"
        // A component outcome carries the owning app's package name, not the component's class.
        completed.packages().outcomes.map { it.packageName } shouldContainExactly listOf("a", "a")
        // Components are the page's to reload; the package data itself did not change.
        coVerify(exactly = 0) { pkgRepo.refresh() }
    }

    @Test
    fun `a component operation names its verb for the history`() {
        val metadata = operation(
            PackageCommand.SetComponents(target = target("a"), entries = emptyList(), enabled = true)
        ).metadata

        metadata.kind shouldBe Operation.Metadata.Kind.COMPONENTS
        metadata.intent shouldBe Operation.Metadata.Intent.ENABLE_COMPONENTS
        metadata.description.get(context) shouldBe "a"
        metadata.pathPlan.shouldBeNull()
    }

    @Test
    fun `a batch is described by its size`() {
        val metadata = operation(PackageCommand.Enable(listOf(target("a"), target("b")))).metadata

        metadata.description.get(context) shouldBe "2 apps"
        metadata.title.get(context) shouldBe "Enable 2 apps"
        metadata.kind.shouldNotBeNull()
    }
}

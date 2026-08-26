package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.installer.AppInstallEvent
import eu.darken.butler.common.pkgs.installer.AppInstallFormat
import eu.darken.butler.common.pkgs.installer.AppInstallPlan
import eu.darken.butler.common.pkgs.installer.AppInstaller
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import kotlin.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppInstallOperationTest : BaseTest() {

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

    private fun operation(appInstaller: AppInstaller = mockk()) = AppInstallOperation(
        installOrigin = Operation.Metadata.Origin.Explorer(Workspace.Id()),
        plan = plan,
        events = MutableSharedFlow(),
        appInstaller = appInstaller,
    )

    @Test
    fun `an install belongs in the operation history, named by its container`() {
        val metadata = operation().metadata

        metadata.kind shouldBe Operation.Metadata.Kind.INSTALL
        // Nothing else identifies the row: an install reports no path changes of its own.
        metadata.intendedPaths shouldBe listOf(source)
    }

    @Test
    fun `a declined confirmation ends the run as cancelled, never as a failure`() = runTest2 {
        val appInstaller = mockk<AppInstaller> {
            every { install(any(), any()) } returns flowOf(AppInstallEvent.Cancelled)
        }
        val context = Operation.Context(id = Operation.Id(), startedAt = Clock.System.now())

        // How every other operation reports a user who called it off, and what keeps the run out of
        // the error notification and badges it Cancelled in the history.
        shouldThrow<CancellationException> {
            operation(appInstaller).perform(context).toList()
        }
    }
}

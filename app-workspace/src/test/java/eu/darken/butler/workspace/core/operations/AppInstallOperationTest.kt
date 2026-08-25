package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.installer.AppInstallFormat
import eu.darken.butler.common.pkgs.installer.AppInstallPlan
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppInstallOperationTest : BaseTest() {

    private val source = LocalPath.build("/sdcard/Download/example.apk")

    private fun operation() = AppInstallOperation(
        installOrigin = Operation.Metadata.Origin.Explorer(Workspace.Id()),
        plan = AppInstallPlan(
            source = source,
            format = AppInstallFormat.APK,
            pkgId = "com.example.app".toPkgId(),
            baseInfo = null,
            splits = listOf(
                AppInstallPlan.Split(entryPath = "example.apk", stagedName = "base.apk", size = 1024L),
            ),
            obbEntries = emptyList(),
            warnings = emptyList(),
        ),
        events = MutableSharedFlow(),
        appInstaller = mockk(),
    )

    @Test
    fun `an install belongs in the operation history, named by its container`() {
        val metadata = operation().metadata

        metadata.kind shouldBe Operation.Metadata.Kind.INSTALL
        // Nothing else identifies the row: an install reports no path changes of its own.
        metadata.intendedPaths shouldBe listOf(source)
    }
}

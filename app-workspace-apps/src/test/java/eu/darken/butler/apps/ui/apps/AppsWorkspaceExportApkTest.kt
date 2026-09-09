package eu.darken.butler.apps.ui.apps

import android.content.pm.PackageInfo
import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.ui.apps.elements.AppsActionBarItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.features.SourceAvailable
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserProfile2
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * A batch export hands the Saver one name per source. An app without an APK source drops out of the
 * URI list, so the names have to be built in the same pass - otherwise every name behind the gap
 * would end up on the wrong app.
 */
class AppsWorkspaceExportApkTest : BaseTest() {

    private fun exportableApp(
        appPackage: String,
        appLabel: String,
        appVersionName: String?,
        appVersionCode: Long,
        apkPath: String,
    ) = appItem(
        pkg = object : SourceAvailable {
            override val id = Pkg.Id(appPackage)
            override val packageInfo = PackageInfo().apply { packageName = appPackage }
            override val label: CaString = appLabel.toCaString()
            override val icon: CaDrawable? = null
            override val userHandle = UserHandle2(0)
            override val sourceDir: APath<*> = LocalPath.build(apkPath)
        },
        packageName = appPackage,
        appLabel = appLabel,
        versionName = appVersionName,
        versionCode = appVersionCode,
    )

    private fun appItem(
        pkg: Installed,
        packageName: String,
        appLabel: String,
        versionName: String?,
        versionCode: Long,
    ) = AppItem(
        pkg = pkg,
        label = appLabel.toCaString(),
        icon = null,
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        appSize = null,
        isSystemApp = false,
        isEnabled = true,
        isUpdatedSystemApp = false,
        installedAt = null,
        updatedAt = null,
        installerInfo = null,
        isSplitApk = false,
        isDebuggable = false,
        userProfile = UserProfile2(handle = UserHandle2(0)),
    )

    private val alpha = exportableApp(
        appPackage = "com.alpha",
        appLabel = "Alpha",
        appVersionName = "1.2",
        appVersionCode = 12,
        apkPath = "/data/app/~~a/com.alpha-1/base.apk",
    )

    // A plain Installed, i.e. no APK source to export.
    private val beta = AppsMockDataProvider.createMockAppItem(
        packageName = "com.beta",
        label = "Beta",
        versionName = "2.3",
        versionCode = 23,
    )

    private val gamma = exportableApp(
        appPackage = "com.gamma",
        appLabel = "Gamma",
        appVersionName = "3.4",
        appVersionCode = 34,
        apkPath = "/data/app/~~g/com.gamma-1/base.apk",
    )

    private val executedActions = mutableListOf<WorkspaceAction>()

    private fun createVM(): AppsWorkspaceViewModel {
        val apps = listOf(alpha, beta, gamma)
        val workspace = mockk<AppsWorkspace>(relaxed = true)
        every { workspace.state } returns flowOf(
            AppsWorkspace.State.Ready(
                apps = apps,
                filteredApps = apps,
                selectedAppIds = apps.map { it.pkg.installId }.toSet(),
                hasRoot = true,
            )
        )
        val id = Workspace.Id()
        return AppsWorkspaceViewModel(
            id = id,
            context = mockk(relaxed = true),
            dispatchers = TestDispatcherProvider(),
            workspaceProvider = mockk<WorkspaceProvider> { every { retrieve(id) } returns flowOf(workspace) },
            workspaceRemote = mockk<WorkspaceRemote>(relaxed = true) {
                coEvery { execute(capture(executedActions)) } returns
                    WorkspaceAction.Create.Result.Success(Workspace.Id())
            },
            appsSettings = mockk(relaxed = true),
            appSizeCache = mockk(relaxed = true),
            tabViewStore = mockk(relaxed = true),
            chromeFactory = mockk {
                every { create(any(), any()) } returns mockk(relaxed = true) {
                    every { operations } returns flowOf(OperationsDisplayState())
                    every { pendingConflicts } returns flowOf(emptyMap())
                }
            },
            operationFocusRequest = OperationFocusRequest(),
        )
    }

    @Test
    fun `a source-less app in the middle does not shift the names behind it`() = runTest {
        val vm = createVM()

        vm.onPageAction(AppsPageAction.ActionBarClick(AppsActionBarItem.ExportApk(listOf(alpha, beta, gamma))))

        val arguments = executedActions.filterIsInstance<WorkspaceAction.Create>()
            .single()
            .arguments
            .shouldBeInstanceOf<SaverArguments.Default>()

        arguments.sourceUris shouldBe listOf(
            "file:///data/app/~~a/com.alpha-1/base.apk",
            "file:///data/app/~~g/com.gamma-1/base.apk",
        )
        arguments.sourceNames shouldBe listOf(
            "Alpha_com.alpha_1.2_12.apk",
            "Gamma_com.gamma_3.4_34.apk",
        )
    }
}

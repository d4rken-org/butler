package eu.darken.butler.apps.core.engine

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.pkgs.AKnownPkg
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.features.InstallerInfo
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserProfile2
import eu.darken.butler.workspace.contracts.apps.AppTag
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class AppItemTest : BaseTest() {

    private fun installerInfo(installerId: Pkg.Id): InstallerInfo = mockk {
        every { allInstallers } returns listOf(mockk { every { id } returns installerId })
    }

    private fun appItem(): AppItem {
        val pkg = mockk<Installed> {
            every { id } returns Pkg.Id("com.test.app")
        }

        return AppItem(
            pkg = pkg,
            label = "Test App".toCaString(),
            icon = null,
            packageName = "com.test.app",
            versionName = "1.0",
            versionCode = 1L,
            appSize = null,
            isSystemApp = false,
            isEnabled = true,
            isUpdatedSystemApp = false,
            installedAt = null,
            updatedAt = null,
            installerInfo = null,
            isSplitApk = false,
            isDebuggable = false,
            userProfile = UserProfile2(handle = UserHandle2(handleId = 0)),
        )
    }

    @Test
    fun `derived values reflect the base item`() {
        val item = appItem()

        item.isSideloaded shouldBe true
        item.tags shouldContain AppTag.Sideloaded
        item.tags shouldNotContain AppTag.System
        item.tags shouldNotContain AppTag.Disabled
    }

    @Test
    fun `copy recomputes derived values when the app becomes a system app`() {
        val item = appItem().copy(isSystemApp = true)

        item.isSideloaded shouldBe false
        item.tags shouldContain AppTag.System
        item.tags shouldNotContain AppTag.Sideloaded
    }

    @Test
    fun `copy recomputes derived values when the app is disabled`() {
        val item = appItem().copy(isEnabled = false)

        item.tags shouldContain AppTag.Disabled
        item.tags shouldContain AppTag.Sideloaded
    }

    @Test
    fun `copy recomputes derived values when an app store installer appears`() {
        val item = appItem().copy(installerInfo = installerInfo(AKnownPkg.GooglePlay.id))

        item.isSideloaded shouldBe false
        item.tags shouldNotContain AppTag.Sideloaded
    }

    @Test
    fun `copy recomputes derived values when the user profile changes`() {
        val item = appItem().copy(
            userProfile = UserProfile2(handle = UserHandle2(handleId = 10), label = "Work"),
        )

        item.tags shouldContain AppTag.User(handleId = 10)
    }

    @Test
    fun `copy keeps derived values that no longer apply out of the tag list`() {
        val sideloaded = appItem()
        sideloaded.tags shouldContain AppTag.Sideloaded

        val fromStore = sideloaded.copy(installerInfo = installerInfo(AKnownPkg.GooglePlay.id))
        fromStore.tags shouldNotContain AppTag.Sideloaded

        val backToSideloaded = fromStore.copy(installerInfo = null)
        backToSideloaded.tags shouldContain AppTag.Sideloaded
    }

    @Test
    fun `tags stay sorted by priority`() {
        val item = appItem().copy(
            isSystemApp = true,
            isEnabled = false,
            isUpdatedSystemApp = true,
            isDebuggable = true,
            isSplitApk = true,
        )

        item.tags shouldBe item.tags.sortedBy { it.priority }
    }
}

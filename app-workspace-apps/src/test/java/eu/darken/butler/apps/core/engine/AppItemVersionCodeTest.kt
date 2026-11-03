package eu.darken.butler.apps.core.engine

import android.content.pm.PackageInfo
import eu.darken.butler.common.pkgs.container.NormalPkg
import eu.darken.butler.common.pkgs.features.InstallerInfo
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.common.user.UserProfile2
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = EmptyApp::class)
class AppItemVersionCodeTest : BaseTest() {

    @Test
    fun `version code is read on API 26`() {
        val pkg = NormalPkg(
            packageInfo = PackageInfo().apply {
                packageName = "com.example.app"
                @Suppress("DEPRECATION")
                versionCode = 42
            },
            installerInfo = InstallerInfo(),
            userHandle = UserHandle2(0),
        )

        val item = AppItem.from(pkg, userProfile = UserProfile2(handle = UserHandle2(0)))

        item.versionCode shouldBe 42L
    }
}

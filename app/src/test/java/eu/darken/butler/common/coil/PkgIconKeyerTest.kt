package eu.darken.butler.common.coil

import android.content.pm.PackageInfo
import coil3.request.Options
import coil3.size.Size
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.user.UserHandle2
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PkgIconKeyerTest : BaseTest() {

    private val keyer = PkgIconKeyer()

    /** 40dp list icon at 3x density. */
    private val options: Options = options(120)

    private fun options(sizePx: Int): Options = mockk<Options>().apply {
        every { size } returns Size(sizePx, sizePx)
    }

    private open class TestPkg(
        override val packageInfo: PackageInfo,
        override val userHandle: UserHandle2,
    ) : Installed {
        override val label: CaString? = null
        override val icon: CaDrawable? = null
    }

    private class OtherTestPkg(
        packageInfo: PackageInfo,
        userHandle: UserHandle2,
    ) : TestPkg(packageInfo, userHandle)

    private fun packageInfo(
        packageName: String = "eu.thirdparty.app",
        versionCode: Long = 1L,
        lastUpdateTime: Long = 1000L,
    ) = PackageInfo().apply {
        this.packageName = packageName
        this.longVersionCode = versionCode
        this.lastUpdateTime = lastUpdateTime
    }

    @Test
    fun `key is stable for an unchanged package`() {
        val first = TestPkg(packageInfo(), UserHandle2(0))
        val second = TestPkg(packageInfo(), UserHandle2(0))

        keyer.key(first, options) shouldBe keyer.key(second, options)
    }

    @Test
    fun `key differs across package names`() {
        val chrome = TestPkg(packageInfo(packageName = "com.android.chrome"), UserHandle2(0))
        val settings = TestPkg(packageInfo(packageName = "com.android.settings"), UserHandle2(0))

        keyer.key(chrome, options) shouldNotBe keyer.key(settings, options)
    }

    @Test
    fun `key differs across user handles`() {
        val personal = TestPkg(packageInfo(), UserHandle2(0))
        val work = TestPkg(packageInfo(), UserHandle2(10))

        keyer.key(personal, options) shouldNotBe keyer.key(work, options)
    }

    @Test
    fun `key differs across version codes`() {
        val old = TestPkg(packageInfo(versionCode = 1L), UserHandle2(0))
        val new = TestPkg(packageInfo(versionCode = 2L), UserHandle2(0))

        keyer.key(old, options) shouldNotBe keyer.key(new, options)
    }

    @Test
    fun `key differs after a reinstall at the same version`() {
        val old = TestPkg(packageInfo(lastUpdateTime = 1000L), UserHandle2(0))
        val new = TestPkg(packageInfo(lastUpdateTime = 2000L), UserHandle2(0))

        keyer.key(old, options) shouldNotBe keyer.key(new, options)
    }

    @Test
    fun `key differs across container classes`() {
        val normal = TestPkg(packageInfo(), UserHandle2(0))
        val other = OtherTestPkg(packageInfo(), UserHandle2(0))

        keyer.key(normal, options) shouldNotBe keyer.key(other, options)
    }

    @Test
    fun `key differs across requested icon sizes`() {
        val pkg = TestPkg(packageInfo(), UserHandle2(0))

        val listIcon = keyer.key(pkg, options(120))
        val gridIcon = keyer.key(pkg, options(168))

        listIcon shouldNotBe gridIcon
    }

    @Test
    fun `key is stable across requests at the same size`() {
        val first = TestPkg(packageInfo(), UserHandle2(0))
        val second = TestPkg(packageInfo(), UserHandle2(0))

        keyer.key(first, options(168)) shouldBe keyer.key(second, options(168))
    }
}

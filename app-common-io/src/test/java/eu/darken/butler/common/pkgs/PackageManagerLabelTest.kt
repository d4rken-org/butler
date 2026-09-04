package eu.darken.butler.common.pkgs

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PackageManagerLabelTest : BaseTest() {

    private val pkgId = "eu.test.pkg".toPkgId()

    private fun packageManager(
        labelRes: Int = 0,
        nonLocalizedLabel: CharSequence? = null,
        resolvedLabel: CharSequence = "",
    ): PackageManager {
        val appInfo = ApplicationInfo().apply {
            packageName = pkgId.name
            this.labelRes = labelRes
            this.nonLocalizedLabel = nonLocalizedLabel
        }
        val pkgInfo = PackageInfo().apply {
            packageName = pkgId.name
            applicationInfo = appInfo
        }
        return mockk {
            every { getPackageInfo(pkgId.name, 0) } returns pkgInfo
            // PackageItemInfo.loadLabel() resolves labelRes through PackageManager.getText().
            every { getText(pkgId.name, labelRes, any()) } returns resolvedLabel
        }
    }

    @Test
    fun `non localized label is used as is`() {
        packageManager(nonLocalizedLabel = "Test App").getLabel2(pkgId) shouldBe "Test App"
    }

    @Test
    fun `label resource is resolved`() {
        packageManager(labelRes = 42, resolvedLabel = "Resolved App").getLabel2(pkgId) shouldBe "Resolved App"
    }

    @Test
    fun `an empty non localized label counts as no label`() {
        packageManager(nonLocalizedLabel = "").getLabel2(pkgId).shouldBeNull()
    }

    @Test
    fun `a label resource resolving to whitespace counts as no label`() {
        packageManager(labelRes = 42, resolvedLabel = "   ").getLabel2(pkgId).shouldBeNull()
    }

    @Test
    fun `a missing package has no label`() {
        val pm = mockk<PackageManager> {
            every { getPackageInfo(pkgId.name, 0) } throws PackageManager.NameNotFoundException()
        }
        pm.getLabel2(pkgId).shouldBeNull()
    }
}

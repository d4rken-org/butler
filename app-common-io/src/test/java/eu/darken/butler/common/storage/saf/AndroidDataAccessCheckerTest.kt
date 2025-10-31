package eu.darken.butler.common.storage.saf

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import eu.darken.butler.common.ApiLevel
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.user.UserHandle2
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AndroidDataAccessCheckerTest : BaseTest() {

    private fun createChecker(
        pkgOps: PkgOps,
        apiLevel: Int
    ): AndroidDataAccessChecker {
        val apiLevelProvider = mockk<ApiLevel> {
            every { has(any()) } answers {
                val requestedLevel = firstArg<Int>()
                apiLevel >= requestedLevel
            }
        }
        return AndroidDataAccessChecker(pkgOps, apiLevelProvider)
    }

    @Test
    fun `test Android below 30 returns false`() = runTest {
        val pkgOps = mockk<PkgOps>()
        val checker = createChecker(pkgOps, apiLevel = 29)

        checker.canUseSAFForAndroidData() shouldBe false
    }

    @Test
    fun `test Android 33 and above returns false`() = runTest {
        val pkgOps = mockk<PkgOps>()
        val checker = createChecker(pkgOps, apiLevel = 33)

        checker.canUseSAFForAndroidData() shouldBe false
    }

    @Test
    fun `test Android 30 with old DocumentsUI returns true`() = runTest {
        val pkgOps = mockk<PkgOps>()
        val packageInfo = PackageInfo().apply {
            versionName = "1.0"
            longVersionCode = 300000000L // Old version
            applicationInfo = ApplicationInfo().apply {
                targetSdkVersion = 30
            }
        }

        coEvery {
            pkgOps.queryPkg(
                pkgName = "com.google.android.documentsui".toPkgId(),
                flags = 0,
                userHandle = UserHandle2()
            )
        } returns packageInfo

        val checker = createChecker(pkgOps, apiLevel = 30)

        checker.canUseSAFForAndroidData() shouldBe true
    }

    @Test
    fun `test Android 30 with new DocumentsUI version returns false`() = runTest {
        val pkgOps = mockk<PkgOps>()
        val packageInfo = PackageInfo().apply {
            versionName = "2.0"
            longVersionCode = 331120000L // New restricted version
            applicationInfo = ApplicationInfo().apply {
                targetSdkVersion = 30
            }
        }

        coEvery {
            pkgOps.queryPkg(
                pkgName = "com.google.android.documentsui".toPkgId(),
                flags = 0,
                userHandle = UserHandle2()
            )
        } returns packageInfo

        val checker = createChecker(pkgOps, apiLevel = 30)

        checker.canUseSAFForAndroidData() shouldBe false
    }

    @Test
    fun `test Android 30 with targetSdk 34 DocumentsUI returns false`() = runTest {
        val pkgOps = mockk<PkgOps>()
        val packageInfo = PackageInfo().apply {
            versionName = "1.0"
            longVersionCode = 300000000L // Old version but...
            applicationInfo = ApplicationInfo().apply {
                targetSdkVersion = 34 // Target SDK 34+
            }
        }

        coEvery {
            pkgOps.queryPkg(
                pkgName = "com.google.android.documentsui".toPkgId(),
                flags = 0,
                userHandle = UserHandle2()
            )
        } returns packageInfo

        val checker = createChecker(pkgOps, apiLevel = 30)

        checker.canUseSAFForAndroidData() shouldBe false
    }

    @Test
    fun `test Android 30 with missing DocumentsUI package returns false`() = runTest {
        val pkgOps = mockk<PkgOps>()

        coEvery {
            pkgOps.queryPkg(
                pkgName = "com.google.android.documentsui".toPkgId(),
                flags = 0,
                userHandle = UserHandle2()
            )
        } returns null

        val checker = createChecker(pkgOps, apiLevel = 30)

        checker.canUseSAFForAndroidData() shouldBe false
    }

    @Test
    fun `test Android 31 with old DocumentsUI returns true`() = runTest {
        val pkgOps = mockk<PkgOps>()
        val packageInfo = PackageInfo().apply {
            versionName = "1.0"
            longVersionCode = 300000000L
            applicationInfo = ApplicationInfo().apply {
                targetSdkVersion = 30
            }
        }

        coEvery {
            pkgOps.queryPkg(
                pkgName = "com.google.android.documentsui".toPkgId(),
                flags = 0,
                userHandle = UserHandle2()
            )
        } returns packageInfo

        val checker = createChecker(pkgOps, apiLevel = 31)

        checker.canUseSAFForAndroidData() shouldBe true
    }

    @Test
    fun `test Android 32 with old DocumentsUI returns true`() = runTest {
        val pkgOps = mockk<PkgOps>()
        val packageInfo = PackageInfo().apply {
            versionName = "1.0"
            longVersionCode = 300000000L
            applicationInfo = ApplicationInfo().apply {
                targetSdkVersion = 30
            }
        }

        coEvery {
            pkgOps.queryPkg(
                pkgName = "com.google.android.documentsui".toPkgId(),
                flags = 0,
                userHandle = UserHandle2()
            )
        } returns packageInfo

        val checker = createChecker(pkgOps, apiLevel = 32)

        checker.canUseSAFForAndroidData() shouldBe true
    }
}

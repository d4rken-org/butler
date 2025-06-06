package eu.darken.butler.common.forensics.csi.source.tools

import eu.darken.butler.common.files.local.LocalPath
import eu.darken.butler.common.forensics.AreaInfo
import eu.darken.butler.common.pkgs.container.PkgArchive
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.user.UserHandle2
import io.kotest.matchers.shouldBe
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest

class DirectApkCheckTest : BaseTest() {

    @MockK lateinit var pkgOps: PkgOps

    @Before fun setup() {
        MockKAnnotations.init(this)
    }

    private fun create() = DirectApkCheck(pkgOps)

    @Test fun testBaseMatch() = runTest {
        val areaInfo = mockk<AreaInfo>().apply {
            every { file } returns LocalPath.build("com.mxtech.ffmpeg.x86", "something.apk")
            every { userHandle } returns UserHandle2(0)
        }
        val testPkg = "com.mxtech.ffmpeg.x86".toPkgId()
        coEvery { pkgOps.viewArchive(any(), any()) } returns mockk<PkgArchive>().apply {
            every { id } returns testPkg
        }

        create().process(areaInfo).apply {
            owners.single().pkgId shouldBe testPkg
        }

        create().process(areaInfo).apply {
            owners.single().pkgId shouldBe testPkg
        }
    }
}
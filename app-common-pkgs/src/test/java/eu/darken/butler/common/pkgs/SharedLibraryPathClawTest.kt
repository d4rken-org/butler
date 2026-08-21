package eu.darken.butler.common.pkgs

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.sources.SharedLibraryPathClaw
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

/**
 * The raw `SharedLibraryInfo` parcels live in `src/test/resources` as `.bin` files. They contain
 * NUL bytes and U+FFFD sequences, which used to make this source file itself binary - `file`
 * reported it as `data` and `grep` silently skipped it during repo-wide searches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedLibraryPathClawTest : BaseTest() {

    private fun create() = SharedLibraryPathClaw()

    @Test
    fun `parse raw parcel for product_app`() {
        val raw = readFixture("sharedlib-parcel-product-app.bin")

        val path = create().clawOutPath("com.google.android.trichromelibrary", raw)
        path shouldBe LocalPath.build("/product/app/TrichromeLibrary/TrichromeLibrary.apk")
    }

    @Test
    fun `parse raw parcel for product_private_app`() {
        val raw = readFixture("sharedlib-parcel-product-priv-app.bin")

        val path = create().clawOutPath("com.google.android.gms", raw)
        path shouldBe LocalPath.build("/product/priv-app/GmsCore/GmsCore.apk")
    }

    @Test
    fun `parse raw parcel for system_app`() {
        val raw = readFixture("sharedlib-parcel-system-app.bin")

        val path = create().clawOutPath("com.google.android.ext.shared", raw)
        path shouldBe LocalPath.build("/system/app/GoogleExtShared/GoogleExtShared.apk")
    }

    @Test
    fun `parse raw parcel for apex_app`() {
        val raw = readFixture("sharedlib-parcel-apex-app.bin")

        val path = create().clawOutPath("com.android.cts.ctsshim", raw)
        path shouldBe LocalPath.build("/apex/com.android.apex.cts.shim/app/CtsShim/CtsShim.apk")
    }

    @Test
    fun `parse raw parcel for random`() {
        val raw = readFixture("sharedlib-parcel-random.bin")

        val path = create().clawOutPath("com.android.cts.ctsshim", raw)
        path shouldBe LocalPath.build("/random/com.android.apex.cts.shim/app/CtsShim/CtsShim.apk")
    }

    @Test
    fun `parse raw parcel for data app - hex encoded`() {
        val raw = readFixture("sharedlib-parcel-data-app.bin")

        val path = create().clawOutPath("com.google.android.trichromelibrary", raw)
        path shouldBe LocalPath.build(
            "/data/app/~~BcOFGTSkKD_f38pun7CSoQ==/com.google.android.trichromelibrary_567263633-dOdlgmZ-tcyeL7sQnABcFw==/TrichromeLibrary.apk",
        )
    }

    /**
     * https://github.com/d4rken-org/butler/issues/539
     * Android 10, Oxygen OS 10.0.1, Oneplus 5
     */
    @Test
    fun `parse raw parcel for issue #539`() {
        val raw = readFixture("sharedlib-parcel-issue-539.bin")

        val path = create().clawOutPath("com.google.android.trichromelibrary", raw)
        path shouldBe LocalPath.build(
            "/data/app/com.google.android.trichromelibrary_579013833-YMIK0G2jD5h6DulG7_idjw==/base.apk",
        )
    }

    private fun readFixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
}

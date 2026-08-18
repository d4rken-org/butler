package eu.darken.butler.common.pkgs.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.local.LocalFileMaterializer
import eu.darken.butler.common.funnel.IPCFunnel
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = EmptyApp::class)
class ApkArchiveParserTest : BaseTest() {

    private val parser = ApkArchiveParser(
        context = mockk<Context>(relaxed = true),
        localFileMaterializer = mockk<LocalFileMaterializer>(relaxed = true),
        ipcFunnel = mockk<IPCFunnel>(relaxed = true),
        dispatcherProvider = TestDispatcherProvider(),
    )

    /** A real self-signed X.509 certificate, DER encoded - the shape an APK actually carries. */
    private val certificateBytes: ByteArray
        get() = javaClass.classLoader!!.getResourceAsStream(CERT_RESOURCE)!!.use { it.readBytes() }

    private fun packageInfo(
        versionName: String? = "1.2.3",
        permissions: Array<String>? = null,
    ) = PackageInfo().apply {
        packageName = "eu.darken.butler.test"
        this.versionName = versionName
        @Suppress("DEPRECATION")
        versionCode = 4711
        requestedPermissions = permissions
        applicationInfo = ApplicationInfo().apply {
            minSdkVersion = 26
            targetSdkVersion = 34
        }
    }

    @Test
    fun `map reads identity, versions and sdks`() {
        val info = parser.map(packageInfo())

        info.id.name shouldBe "eu.darken.butler.test"
        info.versionName shouldBe "1.2.3"
        info.versionCode shouldBe 4711L
        info.minSdk shouldBe 26
        info.targetSdk shouldBe 34
        info.label shouldBe null
        info.icon shouldBe null
    }

    @Test
    fun `map carries the requested permissions`() {
        val info = parser.map(
            packageInfo(permissions = arrayOf("android.permission.INTERNET", "android.permission.CAMERA")),
        )

        info.requestedPermissions shouldContainExactly listOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
        )
    }

    @Test
    fun `map tolerates a manifest without permissions or version name`() {
        val info = parser.map(packageInfo(versionName = null))

        info.versionName shouldBe null
        info.requestedPermissions shouldBe emptyList()
        info.signatures shouldBe emptyList()
    }

    @Test
    fun `map passes label and icon through`() {
        val info = parser.map(packageInfo(), label = "Butler")

        info.label shouldBe "Butler"
    }

    @Test
    fun `a signature resolves to its subject and sha256 fingerprint`() {
        val signature = parser.toApkSignature(Signature(certificateBytes))!!

        signature.subjectDn!! shouldContain "Butler Test Signing"
        signature.sha256 shouldBe EXPECTED_FINGERPRINT
    }

    /** Garbage in the signature block must not lose the fingerprint - only the subject degrades. */
    @Test
    fun `an unparsable certificate still yields a fingerprint`() {
        val signature = parser.toApkSignature(Signature(byteArrayOf(1, 2, 3, 4)))!!

        signature.subjectDn shouldBe null
        signature.sha256.isNotEmpty() shouldBe true
    }

    @Test
    fun `fingerprints are colon separated uppercase hex pairs`() {
        parser.formatFingerprint(byteArrayOf(0x00, 0x0F, 0xAB.toByte(), 0xFF.toByte())) shouldBe "00:0F:AB:FF"
        parser.formatFingerprint(byteArrayOf()) shouldBe ""
    }

    /** Pre-28 there is no SigningInfo, so the deprecated array is the only source. */
    @Test
    @Config(sdk = [26])
    fun `signatures come from the legacy field before API 28`() {
        val info = packageInfo().apply {
            @Suppress("DEPRECATION")
            signatures = arrayOf(Signature(certificateBytes))
        }

        parser.extractSignatures(info).size shouldBe 1
        parser.map(info).signatures.single().sha256 shouldBe EXPECTED_FINGERPRINT
    }

    /** From 28 on the legacy field is stale by contract, so it must not be read. */
    @Test
    fun `the legacy signature field is ignored from API 28 on`() {
        val info = packageInfo().apply {
            @Suppress("DEPRECATION")
            signatures = arrayOf(Signature(certificateBytes))
        }

        parser.extractSignatures(info) shouldBe emptyList()
    }

    @Test
    fun `version text stays readable without a version name`() {
        apkVersionText("1.2.3", 4711L) shouldBe "1.2.3 (4711)"
        apkVersionText(null, 4711L) shouldBe "4711"
        apkVersionText("", 4711L) shouldBe "4711"
        apkVersionText("  ", 4711L) shouldBe "4711"
    }

    /**
     * `ApplicationInfo.loadIcon()` substitutes the framework's generic application icon when the
     * item declares none. Falling back to it would show and export a platform asset as though the
     * archive contained it, so an archive without an icon has to resolve to nothing at all.
     *
     * The context here is a relaxed mock, so a fall-through to `loadIcon()` would hand back a mock
     * drawable rather than null - which is exactly what this asserts against.
     */
    @Test
    fun `an archive without an icon resource resolves to no icon`() {
        val appInfo = ApplicationInfo().apply { icon = 0 }

        parser.resolveIconDrawable(appInfo) shouldBe null
    }

    companion object {
        private const val CERT_RESOURCE = "pkgs/test-signing-cert.der"
        private const val EXPECTED_FINGERPRINT =
            "E6:D0:72:DB:59:1B:AC:9B:71:9F:2A:64:B2:BA:9D:45:B6:3F:CC:DA:F1:75:A3:96:D2:CB:B8:EF:DD:D7:0A:AC"
    }
}

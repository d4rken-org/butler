package eu.darken.butler.common.pkgs.installer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class AppInstallFormatTest : BaseTest() {

    @Test
    fun `detect all four formats`() {
        AppInstallFormat.fromFileName("app.apk") shouldBe AppInstallFormat.APK
        AppInstallFormat.fromFileName("app.apks") shouldBe AppInstallFormat.APKS
        AppInstallFormat.fromFileName("app.xapk") shouldBe AppInstallFormat.XAPK
        AppInstallFormat.fromFileName("app.apkm") shouldBe AppInstallFormat.APKM
    }

    @Test
    fun `detection is case insensitive`() {
        AppInstallFormat.fromFileName("APP.APK") shouldBe AppInstallFormat.APK
        AppInstallFormat.fromFileName("App.XApk") shouldBe AppInstallFormat.XAPK
        AppInstallFormat.fromFileName("App.ApKm") shouldBe AppInstallFormat.APKM
        AppInstallFormat.fromFileName("App.ApKs") shouldBe AppInstallFormat.APKS
    }

    @Test
    fun `only the trailing extension counts`() {
        AppInstallFormat.fromFileName("app.apk.bak") shouldBe null
        AppInstallFormat.fromFileName("app.apks.part") shouldBe null
        AppInstallFormat.fromFileName("apk") shouldBe null
        // ".apk" as a substring elsewhere in the name is not an extension.
        AppInstallFormat.fromFileName("my.apk.notes.txt") shouldBe null
    }

    @Test
    fun `non-installables return null`() {
        AppInstallFormat.fromFileName("backup.tar.gz") shouldBe null
        AppInstallFormat.fromFileName("photos.zip") shouldBe null
        AppInstallFormat.fromFileName("notes.txt") shouldBe null
        AppInstallFormat.fromFileName("noextension") shouldBe null
    }

    @Test
    fun `only a plain apk is not a bundle`() {
        AppInstallFormat.APK.isBundle shouldBe false
        AppInstallFormat.APKS.isBundle shouldBe true
        AppInstallFormat.XAPK.isBundle shouldBe true
        AppInstallFormat.APKM.isBundle shouldBe true
    }
}

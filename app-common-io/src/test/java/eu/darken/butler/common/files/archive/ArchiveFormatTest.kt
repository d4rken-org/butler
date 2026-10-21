package eu.darken.butler.common.files.archive

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ArchiveFormatTest : BaseTest() {

    @Test
    fun `detect zip`() {
        ArchiveFormat.fromFileName("photos.zip") shouldBe ArchiveFormat.ZIP
        ArchiveFormat.fromFileName("PHOTOS.ZIP") shouldBe ArchiveFormat.ZIP
    }

    @Test
    fun `compound tar suffixes win over single extension`() {
        ArchiveFormat.fromFileName("backup.tar.gz") shouldBe ArchiveFormat.TAR_GZ
        ArchiveFormat.fromFileName("backup.tgz") shouldBe ArchiveFormat.TAR_GZ
        ArchiveFormat.fromFileName("backup.tar.bz2") shouldBe ArchiveFormat.TAR_BZ2
        ArchiveFormat.fromFileName("backup.tbz2") shouldBe ArchiveFormat.TAR_BZ2
        ArchiveFormat.fromFileName("backup.tar") shouldBe ArchiveFormat.TAR
    }

    @Test
    fun `app install bundles are browsable zips`() {
        ArchiveFormat.fromFileName("app.apks") shouldBe ArchiveFormat.ZIP
        ArchiveFormat.fromFileName("app.xapk") shouldBe ArchiveFormat.ZIP
        ArchiveFormat.fromFileName("app.apkm") shouldBe ArchiveFormat.ZIP
        ArchiveFormat.fromFileName("APP.XAPK") shouldBe ArchiveFormat.ZIP
        // A plain APK is not browsed, it is viewed.
        ArchiveFormat.fromFileName("app.apk") shouldBe null
    }

    @Test
    fun `stemOf strips bundle extensions whole`() {
        ArchiveFormat.stemOf("app.apks") shouldBe "app"
        ArchiveFormat.stemOf("app.xapk") shouldBe "app"
        ArchiveFormat.stemOf("app.apkm") shouldBe "app"
    }

    @Test
    fun `plain gz is not a browsable archive`() {
        ArchiveFormat.fromFileName("notes.txt.gz") shouldBe null
        ArchiveFormat.fromFileName("archive.gz") shouldBe null
    }

    @Test
    fun `non-archives return null`() {
        ArchiveFormat.fromFileName("song.mp3") shouldBe null
        ArchiveFormat.fromFileName("noextension") shouldBe null
    }

    @Test
    fun `detect rar as a read-only format`() {
        ArchiveFormat.fromFileName("photos.rar") shouldBe ArchiveFormat.RAR
        ArchiveFormat.fromFileName("PHOTOS.RAR") shouldBe ArchiveFormat.RAR
        ArchiveFormat.fromFileName("photos.part1.rar") shouldBe ArchiveFormat.RAR
        ArchiveFormat.RAR.displayExtension shouldBe "rar"
        ArchiveFormat.RAR.isWritable shouldBe false
    }

    @Test
    fun `legacy rar continuation volumes are not detected`() {
        ArchiveFormat.fromFileName("photos.r00") shouldBe null
        ArchiveFormat.fromFileName("photos.r01") shouldBe null
        ArchiveFormat.fromFileName("photos.s00") shouldBe null
    }

    @Test
    fun `stemOf strips the rar extension`() {
        ArchiveFormat.stemOf("photos.rar") shouldBe "photos"
        ArchiveFormat.stemOf("Photos.RAR") shouldBe "Photos"
        ArchiveFormat.stemOf(".rar") shouldBe ".rar"
    }

    @Test
    fun `stemOf strips a rar part suffix whole`() {
        ArchiveFormat.stemOf("photos.part1.rar") shouldBe "photos"
        ArchiveFormat.stemOf("photos.part2.rar") shouldBe "photos"
        ArchiveFormat.stemOf("Photos.PART12.RAR") shouldBe "Photos"
        ArchiveFormat.stemOf("photos.part001.rar") shouldBe "photos"
        ArchiveFormat.stemOf("my.photos.part1.rar") shouldBe "my.photos"
        ArchiveFormat.stemOf("photos.part.rar") shouldBe "photos.part"
        ArchiveFormat.stemOf(".part1.rar") shouldBe ".part1.rar"
    }

    @Test
    fun `detect 7z as a read-only format`() {
        ArchiveFormat.fromFileName("photos.7z") shouldBe ArchiveFormat.SEVEN_Z
        ArchiveFormat.fromFileName("PHOTOS.7Z") shouldBe ArchiveFormat.SEVEN_Z
        ArchiveFormat.stemOf("photos.7z") shouldBe "photos"
        ArchiveFormat.SEVEN_Z.isWritable shouldBe false
        ArchiveFormat.entries.filter { !it.isWritable } shouldBe listOf(ArchiveFormat.SEVEN_Z, ArchiveFormat.RAR)
    }

    @Test
    fun `only zip has a sequential extraction fallback`() {
        ArchiveFormat.entries.filter { it.supportsSequentialExtractionFallback } shouldBe listOf(ArchiveFormat.ZIP)
    }

    @Test
    fun `stemOf strips single and compound extensions`() {
        ArchiveFormat.stemOf("photos.zip") shouldBe "photos"
        ArchiveFormat.stemOf("backup.tar") shouldBe "backup"
        // Compound suffixes strip whole, not just the last component.
        ArchiveFormat.stemOf("backup.tar.gz") shouldBe "backup"
        ArchiveFormat.stemOf("backup.tar.bz2") shouldBe "backup"
        // Alias suffixes.
        ArchiveFormat.stemOf("backup.tgz") shouldBe "backup"
        ArchiveFormat.stemOf("backup.tbz2") shouldBe "backup"
    }

    @Test
    fun `stemOf is case-insensitive`() {
        ArchiveFormat.stemOf("Backup.TAR.GZ") shouldBe "Backup"
        ArchiveFormat.stemOf("PHOTOS.ZIP") shouldBe "PHOTOS"
    }

    @Test
    fun `stemOf keeps a name that is only an extension rather than mangling it`() {
        ArchiveFormat.stemOf(".tar.gz") shouldBe ".tar.gz"
        ArchiveFormat.stemOf(".zip") shouldBe ".zip"
    }

    @Test
    fun `stemOf falls back for names without a recognized archive suffix`() {
        ArchiveFormat.stemOf("noextension") shouldBe "noextension"
        ArchiveFormat.stemOf("notes.txt") shouldBe "notes"
    }
}

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
    fun `plain gz is not a browsable archive`() {
        ArchiveFormat.fromFileName("notes.txt.gz") shouldBe null
        ArchiveFormat.fromFileName("archive.gz") shouldBe null
    }

    @Test
    fun `non-archives return null`() {
        ArchiveFormat.fromFileName("song.mp3") shouldBe null
        ArchiveFormat.fromFileName("noextension") shouldBe null
        ArchiveFormat.fromFileName("archive.rar") shouldBe null
        ArchiveFormat.fromFileName("archive.7z") shouldBe null
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

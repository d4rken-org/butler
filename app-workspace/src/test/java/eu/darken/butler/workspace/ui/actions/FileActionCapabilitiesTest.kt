package eu.darken.butler.workspace.ui.actions

import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.archive.ArchivePathLookup
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.files.smb.SmbPathLookup
import eu.darken.butler.common.pkgs.installer.AppInstallFormat
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FileActionCapabilitiesTest : BaseTest() {

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3A"

    private fun localFile(name: String, fileType: FileType = FileType.FILE) = LocalPathLookup(
        lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
        fileType = fileType,
        size = 1024L,
        modifiedAt = null,
    )

    @Test
    fun `every app install container is installable`() {
        listOf("app.apk", "app.apks", "app.xapk", "app.apkm").forEach { name ->
            FileActionCapabilities.of(localFile(name)).isInstallable shouldBe true
        }
    }

    @Test
    fun `the extension is matched regardless of case`() {
        FileActionCapabilities.of(localFile("APP.APK")).installFormat shouldBe AppInstallFormat.APK
    }

    @Test
    fun `an archive is not an app install container`() {
        val caps = FileActionCapabilities.of(localFile("stuff.zip"))

        caps.isArchiveFile shouldBe true
        caps.isInstallable shouldBe false
    }

    @Test
    fun `an entry inside an archive cannot be extracted as a container of its own`() {
        val caps = FileActionCapabilities.of(
            ArchivePathLookup(
                lookedUp = ArchivePath(
                    container = LocalPath.build("/storage/emulated/0/Download/outer.zip"),
                    segments = listOf("inner.zip"),
                ),
                fileType = FileType.FILE,
                size = 512L,
                modifiedAt = null,
            )
        )

        caps.isArchiveEntry shouldBe true
        caps.archiveFormat shouldBe null
    }

    @Test
    fun `text files are recognized by name`() {
        FileActionCapabilities.of(localFile("notes.txt")).isText shouldBe true
        FileActionCapabilities.of(localFile("app.apk")).isText shouldBe false
    }

    @Test
    fun `a folder is never treated as the file its name looks like`() {
        FileActionCapabilities.of(localFile("app.apk", FileType.DIRECTORY)).isInstallable shouldBe false
        FileActionCapabilities.of(localFile("notes.txt", FileType.DIRECTORY)).isText shouldBe false
        FileActionCapabilities.of(localFile("stuff.zip", FileType.DIRECTORY)).isArchiveFile shouldBe false
    }

    @Test
    fun `only a local file can be handed to another app`() {
        FileActionCapabilities.of(localFile("notes.txt")).canHandOffToOtherApps shouldBe true

        val onServer = SmbPathLookup(
            lookedUp = SmbPath(Uuid.parse("11111111-2222-3333-4444-555555555555"), listOf("notes.txt")),
            fileType = FileType.FILE,
            size = 128L,
            modifiedAt = null,
        )
        FileActionCapabilities.of(onServer).canHandOffToOtherApps shouldBe false

        val viaSaf = SAFPathLookup(
            lookedUp = SAFPath.build(treeUri, "Download", "notes.txt"),
            fileType = FileType.FILE,
            size = 128L,
            modifiedAt = null,
        )
        FileActionCapabilities.of(viaSaf).canHandOffToOtherApps shouldBe false
    }
}

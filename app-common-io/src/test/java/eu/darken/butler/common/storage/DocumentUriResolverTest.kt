package eu.darken.butler.common.storage

import android.net.Uri
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DocumentUriResolverTest : BaseTest() {

    private fun createResolver(volumes: List<StorageVolumeX> = emptyList()): DocumentUriResolver {
        val storageManager2 = mockk<StorageManager2> {
            every { storageVolumes } returns volumes
        }
        return DocumentUriResolver(storageManager2)
    }

    private fun mockPrimaryVolume(directory: File = File("/storage/emulated/0")): StorageVolumeX = mockk {
        every { isPrimary } returns true
        every { uuid } returns null
        every { this@mockk.directory } returns directory
    }

    private fun mockRemovableVolume(
        uuid: String,
        directory: File,
    ): StorageVolumeX = mockk {
        every { isPrimary } returns false
        every { this@mockk.uuid } returns uuid
        every { this@mockk.directory } returns directory
    }

    @Test
    fun `resolve primary root URI`() {
        val primaryVolume = mockPrimaryVolume()
        val resolver = createResolver(listOf(primaryVolume))

        val uri = Uri.parse("content://com.android.externalstorage.documents/root/primary")
        val result = resolver.resolve(uri)

        result shouldBe LocalPath(File("/storage/emulated/0"))
    }

    @Test
    fun `resolve removable storage root URI`() {
        val removableVolume = mockRemovableVolume("FD76-F8FE", File("/storage/FD76-F8FE"))
        val resolver = createResolver(listOf(removableVolume))

        val uri = Uri.parse("content://com.android.externalstorage.documents/root/FD76-F8FE")
        val result = resolver.resolve(uri)

        result shouldBe LocalPath(File("/storage/FD76-F8FE"))
    }

    @Test
    fun `resolve primary document URI with subpath`() {
        val primaryVolume = mockPrimaryVolume()
        val resolver = createResolver(listOf(primaryVolume))

        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
        val result = resolver.resolve(uri)

        result shouldBe LocalPath(File("/storage/emulated/0/Download"))
    }

    @Test
    fun `resolve removable storage document URI with subpath`() {
        val removableVolume = mockRemovableVolume("FD76-F8FE", File("/storage/FD76-F8FE"))
        val resolver = createResolver(listOf(removableVolume))

        val uri = Uri.parse("content://com.android.externalstorage.documents/document/FD76-F8FE%3ADCIM%2FCamera")
        val result = resolver.resolve(uri)

        result shouldBe LocalPath(File("/storage/FD76-F8FE/DCIM/Camera"))
    }

    @Test
    fun `resolve deeply nested path`() {
        val primaryVolume = mockPrimaryVolume()
        val resolver = createResolver(listOf(primaryVolume))

        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata%2Fcom.example.app%2Ffiles")
        val result = resolver.resolve(uri)

        result shouldBe LocalPath(File("/storage/emulated/0/Android/data/com.example.app/files"))
    }

    @Test
    fun `returns null for unknown authority`() {
        val primaryVolume = mockPrimaryVolume()
        val resolver = createResolver(listOf(primaryVolume))

        val uri = Uri.parse("content://com.unknown.provider/root/primary")
        val result = resolver.resolve(uri)

        result.shouldBeNull()
    }

    @Test
    fun `returns null for unknown volume`() {
        val primaryVolume = mockPrimaryVolume()
        val resolver = createResolver(listOf(primaryVolume))

        val uri = Uri.parse("content://com.android.externalstorage.documents/root/UNKNOWN-UUID")
        val result = resolver.resolve(uri)

        result.shouldBeNull()
    }

    @Test
    fun `returns null when volume has no directory`() {
        val volumeWithNoDir = mockk<StorageVolumeX> {
            every { isPrimary } returns true
            every { uuid } returns null
            every { directory } returns null
        }
        val resolver = createResolver(listOf(volumeWithNoDir))

        val uri = Uri.parse("content://com.android.externalstorage.documents/root/primary")
        val result = resolver.resolve(uri)

        result.shouldBeNull()
    }

    @Test
    fun `volume UUID matching is case-insensitive`() {
        val removableVolume = mockRemovableVolume("fd76-f8fe", File("/storage/fd76-f8fe"))
        val resolver = createResolver(listOf(removableVolume))

        val uri = Uri.parse("content://com.android.externalstorage.documents/root/FD76-F8FE")
        val result = resolver.resolve(uri)

        result shouldBe LocalPath(File("/storage/fd76-f8fe"))
    }

    @Test
    fun `selects primary volume when multiple volumes exist`() {
        val primaryVolume = mockPrimaryVolume(File("/storage/emulated/0"))
        val removableVolume = mockRemovableVolume("FD76-F8FE", File("/storage/FD76-F8FE"))
        val resolver = createResolver(listOf(primaryVolume, removableVolume))

        val uri = Uri.parse("content://com.android.externalstorage.documents/root/primary")
        val result = resolver.resolve(uri)

        result shouldBe LocalPath(File("/storage/emulated/0"))
    }

    @Test
    fun `selects correct removable volume when multiple volumes exist`() {
        val primaryVolume = mockPrimaryVolume()
        val removable1 = mockRemovableVolume("ABC1-DEF2", File("/storage/ABC1-DEF2"))
        val removable2 = mockRemovableVolume("FD76-F8FE", File("/storage/FD76-F8FE"))
        val resolver = createResolver(listOf(primaryVolume, removable1, removable2))

        val uri = Uri.parse("content://com.android.externalstorage.documents/root/FD76-F8FE")
        val result = resolver.resolve(uri)

        result shouldBe LocalPath(File("/storage/FD76-F8FE"))
    }

    @Test
    fun `handles empty subpath in document URI`() {
        val primaryVolume = mockPrimaryVolume()
        val resolver = createResolver(listOf(primaryVolume))

        // Document URI with just the volume ID (primary:)
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A")
        val result = resolver.resolve(uri)

        result shouldBe LocalPath(File("/storage/emulated/0"))
    }
}

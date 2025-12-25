package eu.darken.butler.provider.documents

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.provider.documents.core.ButlerDocumentsProvider
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import eu.darken.butler.provider.documents.core.ProviderLocation
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

/**
 * Tests for ButlerDocumentsProvider.isChildDocument()
 *
 * Tests parent-child document relationships for copy/move destination selection.
 */
class ButlerDocumentsProviderTest {

    private lateinit var codec: DocumentIdCodec
    private lateinit var provider: ButlerDocumentsProvider

    @Before
    fun setup() {
        codec = mockk()
        provider = ButlerDocumentsProvider().apply {
            this.codec = this@ButlerDocumentsProviderTest.codec
        }
    }

    @Test
    fun `isChildDocument returns true for direct child LocalPath`() {
        // Given
        val parentId = "local|parent"
        val childId = "local|child"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val childPath = LocalPath.build("/storage/emulated/0/Documents/file.txt")

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(childId) } returns false
        every { codec.decode(parentId) } returns parentPath
        every { codec.decode(childId) } returns childPath

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then
        result shouldBe true
    }

    @Test
    fun `isChildDocument returns true for grandchild LocalPath`() {
        // Given
        val parentId = "local|parent"
        val grandchildId = "local|grandchild"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val grandchildPath = LocalPath.build("/storage/emulated/0/Documents/subfolder/file.txt")

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(grandchildId) } returns false
        every { codec.decode(parentId) } returns parentPath
        every { codec.decode(grandchildId) } returns grandchildPath

        // When
        val result = provider.isChildDocument(parentId, grandchildId)

        // Then
        result shouldBe true
    }

    @Test
    fun `isChildDocument returns false for parent-child reversed`() {
        // Given - child is "parent" of parent (reversed relationship)
        val parentId = "local|parent"
        val childId = "local|child"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents/subfolder")
        val childPath = LocalPath.build("/storage/emulated/0/Documents")

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(childId) } returns false
        every { codec.decode(parentId) } returns parentPath
        every { codec.decode(childId) } returns childPath

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then
        result shouldBe false
    }

    @Test
    fun `isChildDocument returns false for same document`() {
        // Given
        val documentId = "local|doc"
        val path = LocalPath.build("/storage/emulated/0/Documents/file.txt")

        every { codec.isVirtualDocument(documentId) } returns false

        // When
        val result = provider.isChildDocument(documentId, documentId)

        // Then
        result shouldBe false
    }

    @Test
    fun `isChildDocument returns false for sibling paths`() {
        // Given
        val parentId = "local|sibling1"
        val childId = "local|sibling2"
        val sibling1Path = LocalPath.build("/storage/emulated/0/Documents/file1.txt")
        val sibling2Path = LocalPath.build("/storage/emulated/0/Documents/file2.txt")

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(childId) } returns false
        every { codec.decode(parentId) } returns sibling1Path
        every { codec.decode(childId) } returns sibling2Path

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then
        result shouldBe false
    }

    @Test
    fun `isChildDocument returns false for virtual parent document`() {
        // Given
        val parentId = ProviderLocation.Root.Butler.rootDocumentId // "butler"
        val childId = "local|child"

        every { codec.isVirtualDocument(parentId) } returns true

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then
        result shouldBe false
    }

    @Test
    fun `isChildDocument returns false for virtual child document`() {
        // Given
        val parentId = "local|parent"
        val childId = ProviderLocation.Home.Device.documentId // "device|self"

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(childId) } returns true

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then
        result shouldBe false
    }

    @Test
    fun `isChildDocument returns true for SAFPath descendants`() {
        // Given
        val parentId = "saf|parent"
        val childId = "saf|child"
        // SAFPath with same treeRoot but child has additional segments
        val parentPath = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        val childPath = SAFPath.build(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            "subfolder",
            "file.txt"
        )

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(childId) } returns false
        every { codec.decode(parentId) } returns parentPath
        every { codec.decode(childId) } returns childPath

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then
        result shouldBe true
    }

    @Test
    fun `isChildDocument returns false for SAFPath different treeRoots`() {
        // Given
        val parentId = "saf|parent"
        val childId = "saf|child"
        // Different tree roots - Documents vs Downloads
        val parentPath = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        val childPath =
            SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADownloads", "file.txt")

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(childId) } returns false
        every { codec.decode(parentId) } returns parentPath
        every { codec.decode(childId) } returns childPath

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then
        result shouldBe false
    }

    @Test
    fun `isChildDocument returns false for cross-type paths (LocalPath vs SAFPath)`() {
        // Given
        val parentId = "local|parent"
        val childId = "saf|child"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val childPath = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADocuments")

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(childId) } returns false
        every { codec.decode(parentId) } returns parentPath
        every { codec.decode(childId) } returns childPath

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then - LocalPath.isAncestorOf(SAFPath) returns false (type mismatch)
        result shouldBe false
    }

    @Test
    fun `isChildDocument returns false for invalid document IDs`() {
        // Given
        val parentId = "invalid|malformed"
        val childId = "also|invalid"

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(childId) } returns false
        every { codec.decode(parentId) } throws IllegalArgumentException("Invalid document ID")

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then - Safe default on error
        result shouldBe false
    }

    @Test
    fun `isChildDocument handles root filesystem edge case`() {
        // Given
        val parentId = "local|root"
        val childId = "local|child"
        val parentPath = LocalPath.build("/")
        val childPath = LocalPath.build("/storage/emulated/0/Documents/file.txt")

        every { codec.isVirtualDocument(parentId) } returns false
        every { codec.isVirtualDocument(childId) } returns false
        every { codec.decode(parentId) } returns parentPath
        every { codec.decode(childId) } returns childPath

        // When
        val result = provider.isChildDocument(parentId, childId)

        // Then - Everything is a child of root
        result shouldBe true
    }
}

package eu.darken.butler.provider.documents.writer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.provider.documents.core.ButlerDocumentsProvider
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import eu.darken.butler.provider.documents.core.writer.DocumentModifier
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DocumentModifierTest {

    private lateinit var context: Context
    private lateinit var codec: DocumentIdCodec
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var contentResolver: android.content.ContentResolver
    private lateinit var modifier: DocumentModifier

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        codec = mockk()
        gatewaySwitch = mockk()

        // Mock ButlerDocumentsProvider.AUTHORITY to avoid BuildConfig initialization issues
        mockkObject(ButlerDocumentsProvider.Companion)
        every { ButlerDocumentsProvider.AUTHORITY } returns "eu.darken.butler.test.documents"

        // Mock DocumentsContract static methods
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.buildDocumentUri(any(), any()) } answers {
            val authority = args[0] as String
            val documentId = args[1] as String
            mockk<Uri>(relaxed = true).also { uri ->
                every { uri.authority } returns authority
                every { uri.toString() } returns "content://$authority/document/$documentId"
            }
        }
        every { DocumentsContract.buildChildDocumentsUri(any(), any()) } answers {
            val authority = args[0] as String
            val parentDocumentId = args[1] as String
            mockk<Uri>(relaxed = true).also { uri ->
                every { uri.authority } returns authority
                every { uri.toString() } returns "content://$authority/document/$parentDocumentId/children"
            }
        }

        // Mock context.contentResolver
        contentResolver = mockk<android.content.ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver

        modifier = DocumentModifier(
            context = context,
            codec = codec,
            gatewaySwitch = gatewaySwitch,
        )
    }

    // ========== Rename Tests ==========

    @Test
    fun `renameDocument renames file successfully`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/old.txt")
        val newName = "new.txt"
        val destinationPath = LocalPath.build("/storage/emulated/0/Documents/new.txt")
        val newDocumentId = "local|base64new"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"

        every { codec.decode(documentId) } returns sourcePath
        coEvery { gatewaySwitch.exists(destinationPath) } returns false
        coEvery { gatewaySwitch.move(sourcePath, destinationPath) } returns MoveOutcome.Moved
        every { codec.encode(destinationPath) } returns newDocumentId
        every { codec.encode(parentPath) } returns parentId

        // When
        val result = modifier.renameDocument(documentId, newName)

        // Then
        result shouldBe newDocumentId
        coVerify { gatewaySwitch.move(sourcePath, destinationPath) }
    }

    @Test
    fun `renameDocument renames directory successfully`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/OldFolder")
        val newName = "NewFolder"
        val destinationPath = LocalPath.build("/storage/emulated/0/Documents/NewFolder")
        val newDocumentId = "local|base64new"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"

        every { codec.decode(documentId) } returns sourcePath
        coEvery { gatewaySwitch.exists(destinationPath) } returns false
        coEvery { gatewaySwitch.move(sourcePath, destinationPath) } returns MoveOutcome.Moved
        every { codec.encode(destinationPath) } returns newDocumentId
        every { codec.encode(parentPath) } returns parentId

        // When
        val result = modifier.renameDocument(documentId, newName)

        // Then
        result shouldBe newDocumentId
    }

    @Test
    fun `renameDocument throws when destination exists`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/test.txt")
        val newName = "existing.txt"
        val destinationPath = LocalPath.build("/storage/emulated/0/Documents/existing.txt")

        every { codec.decode(documentId) } returns sourcePath
        coEvery { gatewaySwitch.exists(destinationPath) } returns true

        // When/Then
        shouldThrow<IllegalStateException> {
            modifier.renameDocument(documentId, newName)
        }

        coVerify(exactly = 0) { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) }
    }

    @Test
    fun `renameDocument returns same ID when name unchanged`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/test.txt")
        val sameName = "test.txt"

        every { codec.decode(documentId) } returns sourcePath

        // When
        val result = modifier.renameDocument(documentId, sameName)

        // Then
        result shouldBe documentId
        coVerify(exactly = 0) { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) }
    }

    @Test
    fun `renameDocument throws when renaming virtual document`() = runTest {
        // Given - Virtual documents like "butler" or "device|self" should not be renameable
        val documentId = "butler"

        every { codec.decode(documentId) } throws IllegalArgumentException("Virtual document")

        // When/Then
        shouldThrow<IllegalArgumentException> {
            modifier.renameDocument(documentId, "newname")
        }
    }

    // ========== Delete Tests ==========

    @Test
    fun `deleteDocument deletes file successfully`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val path = LocalPath.build("/storage/emulated/0/Documents/test.txt")
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"
        val lookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.FILE
            every { lookedUp } returns path
        }

        every { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } returns lookup
        coEvery { gatewaySwitch.delete(path, recursive = true) } returns true
        every { codec.encode(path) } returns documentId
        every { codec.encode(parentPath) } returns parentId

        // When
        modifier.deleteDocument(documentId)

        // Then
        coVerify { gatewaySwitch.delete(path, recursive = true) }
        verify { context.revokeUriPermission(any<Uri>(), any()) }
    }

    @Test
    fun `deleteDocument deletes directory recursively`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val path = LocalPath.build("/storage/emulated/0/Documents/Folder")
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"
        val lookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.DIRECTORY
            every { lookedUp } returns path
        }

        every { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } returns lookup
        coEvery { gatewaySwitch.lookupFiles(path, any()) } returns emptyList()
        coEvery { gatewaySwitch.delete(path, recursive = true) } returns true
        every { codec.encode(path) } returns documentId
        every { codec.encode(parentPath) } returns parentId

        // When
        modifier.deleteDocument(documentId)

        // Then
        coVerify { gatewaySwitch.delete(path, recursive = true) }
        verify { context.revokeUriPermission(any<Uri>(), any()) }
    }

    @Test
    fun `deleteDocument revokes permissions recursively for directory`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents/Folder")
        val child1Path = LocalPath.build("/storage/emulated/0/Documents/Folder/child1.txt")
        val child2Path = LocalPath.build("/storage/emulated/0/Documents/Folder/child2.txt")

        val parentLookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.DIRECTORY
            every { lookedUp } returns parentPath
        }
        val child1Lookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.FILE
            every { lookedUp } returns child1Path
        }
        val child2Lookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.FILE
            every { lookedUp } returns child2Path
        }

        val grandparentPath = LocalPath.build("/storage/emulated/0/Documents")
        val grandparentId = "local|base64grandparent"

        every { codec.decode(documentId) } returns parentPath
        coEvery { gatewaySwitch.lookup(parentPath, any()) } returns parentLookup
        coEvery { gatewaySwitch.lookupFiles(parentPath, any()) } returns listOf(child1Lookup, child2Lookup)
        coEvery { gatewaySwitch.delete(parentPath, recursive = true) } returns true
        every { codec.encode(parentPath) } returns documentId
        every { codec.encode(grandparentPath) } returns grandparentId
        every { codec.encode(child1Path) } returns "local|child1"
        every { codec.encode(child2Path) } returns "local|child2"

        val uriSlot = mutableListOf<Uri>()
        every { context.revokeUriPermission(capture(uriSlot), any()) } returns Unit

        // When
        modifier.deleteDocument(documentId)

        // Then
        verify(exactly = 3) { context.revokeUriPermission(any<Uri>(), any()) }
        uriSlot.size shouldBe 3  // Parent + 2 children
    }

    @Test
    fun `deleteDocument is idempotent for non-existent paths`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val path = LocalPath.build("/storage/emulated/0/Documents/nonexistent.txt")

        every { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } throws java.io.FileNotFoundException("Not found")

        // When
        modifier.deleteDocument(documentId)

        // Then - No exception thrown, operation succeeds silently
        coVerify(exactly = 0) { gatewaySwitch.delete(any<APath<*>>(), any()) }
    }

    @Test
    fun `deleteDocument throws when deleting virtual document`() = runTest {
        // Given
        val documentId = "butler"

        every { codec.decode(documentId) } throws IllegalArgumentException("Virtual document")

        // When/Then
        shouldThrow<IllegalArgumentException> {
            modifier.deleteDocument(documentId)
        }
    }

    @Test
    fun `deleteDocument uses correct authority in URI`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val path = LocalPath.build("/storage/emulated/0/Documents/test.txt")
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"
        val lookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.FILE
            every { lookedUp } returns path
        }

        every { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } returns lookup
        coEvery { gatewaySwitch.delete(path, recursive = true) } returns true
        every { codec.encode(path) } returns documentId
        every { codec.encode(parentPath) } returns parentId

        val uriSlot = slot<Uri>()
        every { context.revokeUriPermission(capture(uriSlot), any()) } returns Unit

        // When
        modifier.deleteDocument(documentId)

        // Then
        verify { context.revokeUriPermission(any<Uri>(), any()) }
        uriSlot.captured.authority shouldBe ButlerDocumentsProvider.AUTHORITY
    }

    @Test
    fun `deleteDocument revokes both read and write permissions`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val path = LocalPath.build("/storage/emulated/0/Documents/test.txt")
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"
        val lookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.FILE
            every { lookedUp } returns path
        }

        every { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } returns lookup
        coEvery { gatewaySwitch.delete(path, recursive = true) } returns true
        every { codec.encode(path) } returns documentId
        every { codec.encode(parentPath) } returns parentId

        val flagsSlot = slot<Int>()
        every { context.revokeUriPermission(any<Uri>(), capture(flagsSlot)) } returns Unit

        // When
        modifier.deleteDocument(documentId)

        // Then
        verify { context.revokeUriPermission(any<Uri>(), any()) }
        val expectedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        flagsSlot.captured shouldBe expectedFlags
    }

    @Test
    fun `deleteDocument notifies content change on parent directory`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val path = LocalPath.build("/storage/emulated/0/Documents/test.txt")
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"
        val lookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.FILE
            every { lookedUp } returns path
        }

        every { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } returns lookup
        coEvery { gatewaySwitch.delete(path, recursive = true) } returns true
        every { codec.encode(path) } returns documentId
        every { codec.encode(parentPath) } returns parentId

        // When
        modifier.deleteDocument(documentId)

        // Then - Verify notification was sent for parent directory
        verify {
            contentResolver.notifyChange(any(), null)
        }
    }

    @Test
    fun `renameDocument notifies content change on both old and new locations`() = runTest {
        // Given
        val documentId = "local|base64doc"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/old.txt")
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val newName = "new.txt"
        val destinationPath = LocalPath.build("/storage/emulated/0/Documents/new.txt")
        val newDocumentId = "local|base64new"
        val parentId = "local|base64parent"

        every { codec.decode(documentId) } returns sourcePath
        every { codec.encode(destinationPath) } returns newDocumentId
        every { codec.encode(parentPath) } returns parentId
        coEvery { gatewaySwitch.exists(destinationPath) } returns false
        coEvery { gatewaySwitch.move(sourcePath, destinationPath) } returns MoveOutcome.Moved

        // When
        modifier.renameDocument(documentId, newName)

        // Then - Verify notification was sent for old location, new location, and parent
        verify(atLeast = 3) {
            contentResolver.notifyChange(any<Uri>(), null)
        }
    }

    // ========== Step 4: DisplayName validation tests ==========

    @Test
    fun `renameDocument rejects empty displayName`() = runTest {
        shouldThrow<IllegalArgumentException> {
            modifier.renameDocument("local|base64doc", "")
        }
    }

    @Test
    fun `renameDocument rejects displayName with path separator`() = runTest {
        shouldThrow<IllegalArgumentException> {
            modifier.renameDocument("local|base64doc", "../../../etc/passwd")
        }
    }

    @Test
    fun `renameDocument rejects displayName dot`() = runTest {
        shouldThrow<IllegalArgumentException> {
            modifier.renameDocument("local|base64doc", ".")
        }
    }

    @Test
    fun `renameDocument rejects displayName dotdot`() = runTest {
        shouldThrow<IllegalArgumentException> {
            modifier.renameDocument("local|base64doc", "..")
        }
    }

    @Test
    fun `renameDocument accepts dotfile name like dotgitignore`() = runTest {
        val documentId = "local|base64doc"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/old.txt")
        val newName = ".gitignore"
        val destinationPath = LocalPath.build("/storage/emulated/0/Documents/.gitignore")
        val newDocumentId = "local|base64new"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"

        every { codec.decode(documentId) } returns sourcePath
        coEvery { gatewaySwitch.exists(destinationPath) } returns false
        coEvery { gatewaySwitch.move(sourcePath, destinationPath) } returns MoveOutcome.Moved
        every { codec.encode(destinationPath) } returns newDocumentId
        every { codec.encode(parentPath) } returns parentId

        val result = modifier.renameDocument(documentId, newName)
        result shouldBe newDocumentId
    }

    // ========== Step 8: Edge case tests ==========

    @Test
    fun `deleteDocument where lookup succeeds but delete throws FileNotFoundException`() = runTest {
        val documentId = "local|base64doc"
        val path = LocalPath.build("/storage/emulated/0/Documents/race-condition.txt")
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"
        val lookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.FILE
            every { lookedUp } returns path
        }

        every { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } returns lookup
        coEvery { gatewaySwitch.delete(path, recursive = true) } throws java.io.FileNotFoundException("Race condition")
        every { codec.encode(path) } returns documentId
        every { codec.encode(parentPath) } returns parentId

        // Should not throw - delete is idempotent for FileNotFoundException
        modifier.deleteDocument(documentId)
    }

    @Test
    fun `deleteDocument where lookupFiles fails during recursive permission revocation`() = runTest {
        val documentId = "local|base64doc"
        val path = LocalPath.build("/storage/emulated/0/Documents/Folder")
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val parentId = "local|base64parent"
        val lookup = mockk<APathLookup<APath<*>>> {
            every { fileType } returns FileType.DIRECTORY
            every { lookedUp } returns path
        }

        every { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } returns lookup
        coEvery { gatewaySwitch.lookupFiles(path, any()) } throws RuntimeException("Permission denied")
        coEvery { gatewaySwitch.delete(path, recursive = true) } returns true
        every { codec.encode(path) } returns documentId
        every { codec.encode(parentPath) } returns parentId

        // Should still proceed with delete even if permission revocation fails for children
        modifier.deleteDocument(documentId)

        coVerify { gatewaySwitch.delete(path, recursive = true) }
        // Still revokes the parent document's permissions
        verify { context.revokeUriPermission(any<Uri>(), any()) }
    }

    @Test
    fun `renameDocument throws for root path with no parent`() = runTest {
        val documentId = "local|base64root"
        val rootPath = LocalPath.build("/")

        every { codec.decode(documentId) } returns rootPath

        shouldThrow<IllegalArgumentException> {
            modifier.renameDocument(documentId, "newname")
        }
    }
}

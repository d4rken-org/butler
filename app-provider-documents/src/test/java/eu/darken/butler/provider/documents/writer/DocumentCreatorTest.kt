package eu.darken.butler.provider.documents.writer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.provider.documents.core.ButlerDocumentsProvider
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import eu.darken.butler.provider.documents.core.writer.DocumentCreator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import java.io.FileNotFoundException

class DocumentCreatorTest {

    private lateinit var context: Context
    private lateinit var codec: DocumentIdCodec
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var contentResolver: ContentResolver
    private lateinit var creator: DocumentCreator

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        codec = mockk()
        gatewaySwitch = mockk()
        contentResolver = mockk(relaxed = true)

        // Mock ButlerDocumentsProvider.AUTHORITY
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
        every { context.contentResolver } returns contentResolver

        creator = DocumentCreator(
            context = context,
            codec = codec,
            gatewaySwitch = gatewaySwitch,
        )
    }

    @Test
    fun `createDocument creates file in existing directory`() = runTest {
        // Given
        val parentId = "local|base64parent"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val mimeType = "text/plain"
        val displayName = "test.txt"
        val createdPath = LocalPath.build("/storage/emulated/0/Documents/test.txt")
        val createdId = "local|base64created"

        every { codec.decode(parentId) } returns parentPath
        coEvery { gatewaySwitch.exists(parentPath.child(displayName)) } returns false
        coEvery { gatewaySwitch.createFile(any(), any()) } returns Unit
        every { codec.encode(createdPath) } returns createdId
        every { codec.encode(parentPath) } returns parentId

        // When
        val result = creator.createDocument(parentId, mimeType, displayName)

        // Then
        result shouldBe createdId
        coVerify { gatewaySwitch.createFile(parentPath.child(displayName), createParents = false) }
    }

    @Test
    fun `createDocument creates directory when MIME_TYPE_DIR`() = runTest {
        // Given
        val parentId = "local|base64parent"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val mimeType = DocumentsContract.Document.MIME_TYPE_DIR
        val displayName = "NewFolder"
        val createdPath = LocalPath.build("/storage/emulated/0/Documents/NewFolder")
        val createdId = "local|base64created"

        every { codec.decode(parentId) } returns parentPath
        coEvery { gatewaySwitch.exists(parentPath.child(displayName)) } returns false
        coEvery { gatewaySwitch.createDir(any(), any()) } returns Unit
        every { codec.encode(createdPath) } returns createdId
        every { codec.encode(parentPath) } returns parentId

        // When
        val result = creator.createDocument(parentId, mimeType, displayName)

        // Then
        result shouldBe createdId
        coVerify { gatewaySwitch.createDir(parentPath.child(displayName), createParents = false) }
    }

    @Test
    fun `createDocument handles name conflict by generating unique name`() = runTest {
        // Given
        val parentId = "local|base64parent"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val mimeType = "text/plain"
        val displayName = "test.txt"
        val originalPath = parentPath.child(displayName)
        val uniquePath = parentPath.child("test (1).txt")
        val createdId = "local|base64created"

        every { codec.decode(parentId) } returns parentPath
        coEvery { gatewaySwitch.exists(originalPath) } returns true  // Conflict!
        coEvery { gatewaySwitch.exists(uniquePath) } returns false   // (1) is available
        coEvery { gatewaySwitch.createFile(uniquePath, false) } returns Unit
        every { codec.encode(uniquePath) } returns createdId
        every { codec.encode(parentPath) } returns parentId

        // When
        val result = creator.createDocument(parentId, mimeType, displayName)

        // Then
        result shouldBe createdId
        coVerify { gatewaySwitch.createFile(uniquePath, createParents = false) }
    }

    @Test
    fun `createDocument handles multiple conflicts`() = runTest {
        // Given
        val parentId = "local|base64parent"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val mimeType = "text/plain"
        val displayName = "test.txt"
        val originalPath = parentPath.child("test.txt")
        val path1 = parentPath.child("test (1).txt")
        val path2 = parentPath.child("test (2).txt")
        val path3 = parentPath.child("test (3).txt")
        val createdId = "local|base64created"

        every { codec.decode(parentId) } returns parentPath
        coEvery { gatewaySwitch.exists(originalPath) } returns true  // Original conflicts
        coEvery { gatewaySwitch.exists(path1) } returns true         // (1) conflicts
        coEvery { gatewaySwitch.exists(path2) } returns true         // (2) conflicts
        coEvery { gatewaySwitch.exists(path3) } returns false        // (3) is available
        coEvery { gatewaySwitch.createFile(path3, false) } returns Unit
        every { codec.encode(path3) } returns createdId
        every { codec.encode(parentPath) } returns parentId

        // When
        val result = creator.createDocument(parentId, mimeType, displayName)

        // Then
        result shouldBe createdId
        coVerify { gatewaySwitch.createFile(path3, createParents = false) }
    }

    @Test
    fun `createDocument throws when parent does not exist`() = runTest {
        // Given
        val parentId = "local|base64parent"
        val parentPath = LocalPath.build("/storage/emulated/0/NonExistent")
        val mimeType = "text/plain"
        val displayName = "test.txt"

        every { codec.decode(parentId) } returns parentPath
        coEvery { gatewaySwitch.exists(any()) } returns false
        coEvery { gatewaySwitch.createFile(any(), any()) } throws FileNotFoundException("Parent not found")

        // When/Then
        shouldThrow<FileNotFoundException> {
            creator.createDocument(parentId, mimeType, displayName)
        }
    }

    @Test
    fun `createDocument throws when parent is not a directory`() = runTest {
        // Given
        val parentId = "local|base64file"
        val parentPath = LocalPath.build("/storage/emulated/0/document.txt")
        val mimeType = "text/plain"
        val displayName = "test.txt"

        every { codec.decode(parentId) } returns parentPath
        coEvery { gatewaySwitch.exists(any()) } returns false
        coEvery { gatewaySwitch.createFile(any(), any()) } throws IllegalArgumentException("Parent is not a directory")

        // When/Then
        shouldThrow<IllegalArgumentException> {
            creator.createDocument(parentId, mimeType, displayName)
        }
    }

    @Test
    fun `createDocument encodes created path correctly`() = runTest {
        // Given
        val parentId = "local|base64parent"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val mimeType = "text/plain"
        val displayName = "test.txt"
        val createdPath = parentPath.child(displayName)
        val createdId = "local|base64created"

        every { codec.decode(parentId) } returns parentPath
        coEvery { gatewaySwitch.exists(createdPath) } returns false
        coEvery { gatewaySwitch.createFile(any(), any()) } returns Unit

        val pathSlot = slot<LocalPath>()
        every { codec.encode(capture(pathSlot)) } returns createdId
        every { codec.encode(parentPath) } returns parentId

        // When
        creator.createDocument(parentId, mimeType, displayName)

        // Then
        pathSlot.captured shouldBe createdPath
    }

    @Test
    fun `createDocument handles SAF parent paths`() = runTest {
        // Given
        val parentId = "saf|base64parent"
        val parentPath = mockk<eu.darken.butler.common.files.SAFPath>()
        val childPath = mockk<eu.darken.butler.common.files.SAFPath>()
        val mimeType = "text/plain"
        val displayName = "test.txt"
        val createdId = "saf|base64created"

        every { codec.decode(parentId) } returns parentPath
        every { parentPath.child(displayName) } returns childPath
        coEvery { gatewaySwitch.exists(childPath) } returns false
        coEvery { gatewaySwitch.createFile(childPath, false) } returns Unit
        every { codec.encode(childPath) } returns createdId
        every { codec.encode(parentPath) } returns parentId

        // When
        val result = creator.createDocument(parentId, mimeType, displayName)

        // Then
        result shouldBe createdId
        coVerify { gatewaySwitch.createFile(childPath, createParents = false) }
    }

    @Test
    fun `createDocument returns non-null document ID`() = runTest {
        // Given
        val parentId = "local|base64parent"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val mimeType = "text/plain"
        val displayName = "test.txt"

        every { codec.decode(parentId) } returns parentPath
        coEvery { gatewaySwitch.exists(any()) } returns false
        coEvery { gatewaySwitch.createFile(any(), any()) } returns Unit
        every { codec.encode(parentPath) } returns parentId
        every { codec.encode(parentPath.child(displayName)) } returns "local|base64created"

        // When
        val result = creator.createDocument(parentId, mimeType, displayName)

        // Then
        result shouldNotBe null
    }

    @Test
    fun `createDocument notifies content change on parent directory`() = runTest {
        // Given
        val parentId = "local|base64parent"
        val parentPath = LocalPath.build("/storage/emulated/0/Documents")
        val mimeType = "text/plain"
        val displayName = "test.txt"
        val createdId = "local|base64created"

        every { codec.decode(parentId) } returns parentPath
        coEvery { gatewaySwitch.exists(any()) } returns false
        coEvery { gatewaySwitch.createFile(any(), any()) } returns Unit
        every { codec.encode(parentPath) } returns parentId
        every { codec.encode(parentPath.child(displayName)) } returns createdId

        // When
        creator.createDocument(parentId, mimeType, displayName)

        // Then - Verify notification was sent for parent directory
        verify {
            contentResolver.notifyChange(any(), null)
        }
    }
}

package eu.darken.butler.provider.documents.writer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.provider.documents.ButlerDocumentsProvider
import eu.darken.butler.provider.documents.core.DocumentIdCodec
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException

class DocumentMoverTest {

    private lateinit var context: Context
    private lateinit var codec: DocumentIdCodec
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var contentResolver: ContentResolver
    private lateinit var mover: DocumentMover

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

        mover = DocumentMover(
            context = context,
            codec = codec,
            gatewaySwitch = gatewaySwitch,
        )
    }

    // Helper to create a properly typed CopyAction.State.Completed
    @Suppress("UNCHECKED_CAST")
    private fun createCopyState(resultPath: APath<*>): CopyAction.State<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>> {
        val sourceLookup = mockk<APathLookup<APath<*>>>(relaxed = true)
        val copiedLookup = mockk<APathLookup<APath<*>>> {
            every { lookedUp } returns resultPath
        }
        return CopyAction.State.Completed(
            copied = setOf(sourceLookup to copiedLookup),
            copiedBytes = 0L
        ) as CopyAction.State<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>>
    }

    // Helper to create a properly typed MoveAction.State.Completed
    @Suppress("UNCHECKED_CAST")
    private fun createMoveState(resultPath: APath<*>): MoveAction.State<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>> {
        val sourceLookup = mockk<APathLookup<APath<*>>>(relaxed = true)
        val movedLookup = mockk<APathLookup<APath<*>>> {
            every { lookedUp } returns resultPath
        }
        return MoveAction.State.Completed(
            movedFiles = setOf(sourceLookup to movedLookup),
            bytesMoved = 0L
        ) as MoveAction.State<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>>
    }

    // ========== copyDocument Tests ==========

    @Test
    fun `copyDocument copies file to new parent`() = runTest {
        // Given
        val sourceId = "local|source"
        val targetParentId = "local|targetParent"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/file.txt")
        val targetParentPath = LocalPath.build("/storage/emulated/0/Downloads")
        val copiedPath = LocalPath.build("/storage/emulated/0/Downloads/file.txt")
        val copiedId = "local|copied"

        every { codec.isVirtualDocument(sourceId) } returns false
        every { codec.isVirtualDocument(targetParentId) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(any()) } returns false

        coEvery { gatewaySwitch.copy(any(), any(), any(), any()) } returns flowOf(createCopyState(copiedPath))

        every { codec.encode(copiedPath) } returns copiedId

        // When
        val result = mover.copyDocument(sourceId, targetParentId)

        // Then
        result shouldBe copiedId
        coVerify { gatewaySwitch.copy(setOf(sourcePath), targetParentPath, any(), any()) }
        verify { contentResolver.notifyChange(any(), null) }
    }

    @Test
    fun `copyDocument handles name conflict by generating unique name`() = runTest {
        // Given
        val sourceId = "local|source"
        val targetParentId = "local|targetParent"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/file.txt")
        val targetParentPath = LocalPath.build("/storage/emulated/0/Downloads")
        val conflictPath = LocalPath.build("/storage/emulated/0/Downloads/file.txt")
        val uniquePath = LocalPath.build("/storage/emulated/0/Downloads/file (1).txt")
        val copiedId = "local|copied"

        every { codec.isVirtualDocument(sourceId) } returns false
        every { codec.isVirtualDocument(targetParentId) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(conflictPath) } returns true
        coEvery { gatewaySwitch.exists(uniquePath) } returns false

        coEvery { gatewaySwitch.copy(any(), any(), any(), any()) } returns flowOf(createCopyState(uniquePath))

        every { codec.encode(uniquePath) } returns copiedId

        // When
        val result = mover.copyDocument(sourceId, targetParentId)

        // Then
        result shouldBe copiedId
        coVerify { gatewaySwitch.exists(conflictPath) }
        coVerify { gatewaySwitch.exists(uniquePath) }
    }

    @Test
    fun `copyDocument works with SAF paths`() = runTest {
        // Given
        val sourceId = "saf|source"
        val targetParentId = "saf|targetParent"
        val sourcePath = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADocuments", "file.txt")
        val targetParentPath = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADownloads")
        val copiedPath = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADownloads", "file.txt")
        val copiedId = "saf|copied"

        every { codec.isVirtualDocument(sourceId) } returns false
        every { codec.isVirtualDocument(targetParentId) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(any()) } returns false

        coEvery { gatewaySwitch.copy(any(), any(), any(), any()) } returns flowOf(createCopyState(copiedPath))

        every { codec.encode(copiedPath) } returns copiedId

        // When
        val result = mover.copyDocument(sourceId, targetParentId)

        // Then
        result shouldBe copiedId
    }

    @Test
    fun `copyDocument throws for virtual source document`() = runTest {
        // Given
        val sourceId = "butler"
        val targetParentId = "local|target"

        every { codec.isVirtualDocument(sourceId) } returns true

        // When/Then
        val exception = shouldThrow<IllegalArgumentException> {
            mover.copyDocument(sourceId, targetParentId)
        }
        exception.message shouldNotBe null
    }

    @Test
    fun `copyDocument throws for virtual target document`() = runTest {
        // Given
        val sourceId = "local|source"
        val targetParentId = "butler"
        val sourcePath = LocalPath.build("/storage/emulated/0/file.txt")

        every { codec.isVirtualDocument(sourceId) } returns false
        every { codec.isVirtualDocument(targetParentId) } returns true
        every { codec.decode(sourceId) } returns sourcePath

        // When/Then
        val exception = shouldThrow<IllegalArgumentException> {
            mover.copyDocument(sourceId, targetParentId)
        }
        exception.message shouldNotBe null
    }

    @Test
    fun `copyDocument notifies target parent changed`() = runTest {
        // Given
        val sourceId = "local|source"
        val targetParentId = "local|targetParent"
        val sourcePath = LocalPath.build("/storage/emulated/0/file.txt")
        val targetParentPath = LocalPath.build("/storage/emulated/0/Downloads")
        val copiedPath = LocalPath.build("/storage/emulated/0/Downloads/file.txt")

        every { codec.isVirtualDocument(sourceId) } returns false
        every { codec.isVirtualDocument(targetParentId) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(any()) } returns false

        coEvery { gatewaySwitch.copy(any(), any(), any(), any()) } returns flowOf(createCopyState(copiedPath))
        every { codec.encode(copiedPath) } returns "local|copied"

        // When
        mover.copyDocument(sourceId, targetParentId)

        // Then
        val uriSlot = slot<Uri>()
        verify { contentResolver.notifyChange(capture(uriSlot), null) }
        uriSlot.captured.toString() shouldNotBe null
    }

    @Test
    fun `copyDocument returns non-null document ID`() = runTest {
        // Given
        val sourceId = "local|source"
        val targetParentId = "local|target"
        val sourcePath = LocalPath.build("/storage/emulated/0/file.txt")
        val targetParentPath = LocalPath.build("/storage/emulated/0/Downloads")
        val copiedPath = LocalPath.build("/storage/emulated/0/Downloads/file.txt")

        every { codec.isVirtualDocument(any()) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(any()) } returns false

        coEvery { gatewaySwitch.copy(any(), any(), any(), any()) } returns flowOf(createCopyState(copiedPath))
        every { codec.encode(copiedPath) } returns "local|copiedId"

        // When
        val result = mover.copyDocument(sourceId, targetParentId)

        // Then
        result shouldNotBe null
        result shouldBe "local|copiedId"
    }

    // ========== moveDocument Tests ==========

    @Test
    fun `moveDocument moves file to new parent`() = runTest {
        // Given
        val sourceId = "local|source"
        val sourceParentId = "local|sourceParent"
        val targetParentId = "local|targetParent"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/file.txt")
        val targetParentPath = LocalPath.build("/storage/emulated/0/Downloads")
        val movedPath = LocalPath.build("/storage/emulated/0/Downloads/file.txt")
        val movedId = "local|moved"

        every { codec.isVirtualDocument(sourceId) } returns false
        every { codec.isVirtualDocument(targetParentId) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(any()) } returns false

        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } returns flowOf(createMoveState(movedPath))

        every { codec.encode(movedPath) } returns movedId

        // When
        val result = mover.moveDocument(sourceId, sourceParentId, targetParentId)

        // Then
        result shouldBe movedId
        coVerify { gatewaySwitch.move(setOf(sourcePath), targetParentPath, any(), any()) }
    }

    @Test
    fun `moveDocument returns same ID if source equals target parent (no-op)`() = runTest {
        // Given
        val sourceId = "local|source"
        val parentId = "local|parent"

        // When
        val result = mover.moveDocument(sourceId, parentId, parentId)

        // Then
        result shouldBe sourceId
        coVerify(exactly = 0) { gatewaySwitch.move(any(), any(), any(), any()) }
    }

    @Test
    fun `moveDocument handles name conflicts`() = runTest {
        // Given
        val sourceId = "local|source"
        val sourceParentId = "local|sourceParent"
        val targetParentId = "local|targetParent"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/file.txt")
        val targetParentPath = LocalPath.build("/storage/emulated/0/Downloads")
        val conflictPath = LocalPath.build("/storage/emulated/0/Downloads/file.txt")
        val uniquePath = LocalPath.build("/storage/emulated/0/Downloads/file (1).txt")

        every { codec.isVirtualDocument(any()) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(conflictPath) } returns true
        coEvery { gatewaySwitch.exists(uniquePath) } returns false

        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } returns flowOf(createMoveState(uniquePath))
        every { codec.encode(uniquePath) } returns "local|moved"

        // When
        val result = mover.moveDocument(sourceId, sourceParentId, targetParentId)

        // Then
        result shouldBe "local|moved"
        coVerify { gatewaySwitch.exists(conflictPath) }
        coVerify { gatewaySwitch.exists(uniquePath) }
    }

    @Test
    fun `moveDocument throws for virtual source document`() = runTest {
        // Given
        val sourceId = "butler"
        val sourceParentId = "local|parent1"
        val targetParentId = "local|parent2"

        every { codec.isVirtualDocument(sourceId) } returns true

        // When/Then
        val exception = shouldThrow<IllegalArgumentException> {
            mover.moveDocument(sourceId, sourceParentId, targetParentId)
        }
        exception.message shouldNotBe null
    }

    @Test
    fun `moveDocument throws for virtual target document`() = runTest {
        // Given
        val sourceId = "local|source"
        val sourceParentId = "local|parent1"
        val targetParentId = "butler"
        val sourcePath = LocalPath.build("/storage/emulated/0/file.txt")

        every { codec.isVirtualDocument(sourceId) } returns false
        every { codec.isVirtualDocument(targetParentId) } returns true
        every { codec.decode(sourceId) } returns sourcePath

        // When/Then
        val exception = shouldThrow<IllegalArgumentException> {
            mover.moveDocument(sourceId, sourceParentId, targetParentId)
        }
        exception.message shouldNotBe null
    }

    @Test
    fun `moveDocument notifies BOTH parents changed`() = runTest {
        // Given
        val sourceId = "local|source"
        val sourceParentId = "local|sourceParent"
        val targetParentId = "local|targetParent"
        val sourcePath = LocalPath.build("/storage/emulated/0/Documents/file.txt")
        val targetParentPath = LocalPath.build("/storage/emulated/0/Downloads")
        val movedPath = LocalPath.build("/storage/emulated/0/Downloads/file.txt")

        every { codec.isVirtualDocument(any()) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(any()) } returns false

        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } returns flowOf(createMoveState(movedPath))
        every { codec.encode(movedPath) } returns "local|moved"

        // When
        mover.moveDocument(sourceId, sourceParentId, targetParentId)

        // Then - Should notify both source and target parents
        verify(exactly = 2) { contentResolver.notifyChange(any(), null) }
    }

    @Test
    fun `moveDocument works with cross-gateway moves (LocalPath to SAFPath)`() = runTest {
        // Given
        val sourceId = "local|source"
        val sourceParentId = "local|sourceParent"
        val targetParentId = "saf|targetParent"
        val sourcePath = LocalPath.build("/storage/emulated/0/file.txt")
        val targetParentPath = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADownloads")
        val movedPath = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3ADownloads", "file.txt")

        every { codec.isVirtualDocument(any()) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(any()) } returns false

        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } returns flowOf(createMoveState(movedPath))
        every { codec.encode(movedPath) } returns "saf|moved"

        // When
        val result = mover.moveDocument(sourceId, sourceParentId, targetParentId)

        // Then
        result shouldBe "saf|moved"
    }

    @Test
    fun `moveDocument returns correct document ID`() = runTest {
        // Given
        val sourceId = "local|source"
        val sourceParentId = "local|sourceParent"
        val targetParentId = "local|targetParent"
        val sourcePath = LocalPath.build("/storage/emulated/0/file.txt")
        val targetParentPath = LocalPath.build("/storage/emulated/0/Downloads")
        val movedPath = LocalPath.build("/storage/emulated/0/Downloads/file.txt")

        every { codec.isVirtualDocument(any()) } returns false
        every { codec.decode(sourceId) } returns sourcePath
        every { codec.decode(targetParentId) } returns targetParentPath
        coEvery { gatewaySwitch.exists(any()) } returns false

        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } returns flowOf(createMoveState(movedPath))
        every { codec.encode(movedPath) } returns "local|movedId"

        // When
        val result = mover.moveDocument(sourceId, sourceParentId, targetParentId)

        // Then
        result shouldNotBe null
        result shouldBe "local|movedId"
    }
}

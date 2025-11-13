package eu.darken.butler.provider.documents.query

import android.content.Context
import android.provider.DocumentsContract.Document.*
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import eu.darken.butler.provider.documents.core.ProviderLocation
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DocumentQueryHandlerTest {

    private lateinit var context: Context
    private lateinit var codec: DocumentIdCodec
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var handler: DocumentQueryHandler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        codec = mockk()
        gatewaySwitch = mockk()
        handler = DocumentQueryHandler(context, codec, gatewaySwitch)
    }

    @Test
    fun `queryDocument for Butler root returns virtual document`() = runTest {
        val cursor = handler.queryDocument(ProviderLocation.Root.Butler.rootDocumentId, null)

        cursor.count shouldBe 1
        cursor.moveToFirst() shouldBe true

        val docIdIndex = cursor.getColumnIndex(COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(COLUMN_MIME_TYPE)

        cursor.getString(docIdIndex) shouldBe "butler"
        cursor.getString(nameIndex) shouldNotBe null
        cursor.getString(mimeIndex) shouldBe MIME_TYPE_DIR
    }

    @Test
    fun `queryDocument for Device home returns virtual document`() = runTest {
        val cursor = handler.queryDocument(ProviderLocation.Home.Device.documentId, null)

        cursor.count shouldBe 1
        cursor.moveToFirst() shouldBe true

        val docIdIndex = cursor.getColumnIndex(COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(COLUMN_MIME_TYPE)

        cursor.getString(docIdIndex) shouldBe "device|self"
        cursor.getString(nameIndex) shouldNotBe null
        cursor.getString(mimeIndex) shouldBe MIME_TYPE_DIR
    }

    @Test
    fun `queryDocument for real path decodes and uses gateway`() = runTest {
        val path = LocalPath.build("/test/file.txt")
        val documentId = "local|base64test"

        val mockLookup = mockk<APathLookup<APath<*>>> {
            coEvery { lookedUp } returns path
            coEvery { name } returns "file.txt"
            coEvery { fileType } returns FileType.FILE
            coEvery { size } returns 1024L
            coEvery { modifiedAt } returns Instant.fromEpochMilliseconds(1000000)
        }

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } returns mockLookup

        val cursor = handler.queryDocument(documentId, null)

        cursor.count shouldBe 1
        cursor.moveToFirst() shouldBe true

        val nameIndex = cursor.getColumnIndex(COLUMN_DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(COLUMN_SIZE)

        cursor.getString(nameIndex) shouldBe "file.txt"
        cursor.getLong(sizeIndex) shouldBe 1024L
    }

    @Test
    fun `queryChildDocuments for Butler root returns Device home`() = runTest {
        val cursor = handler.queryChildDocuments(
            ProviderLocation.Root.Butler.rootDocumentId,
            null,
            null
        )

        cursor.count shouldBe 1
        cursor.moveToFirst() shouldBe true

        val docIdIndex = cursor.getColumnIndex(COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndex(COLUMN_DISPLAY_NAME)

        cursor.getString(docIdIndex) shouldBe "device|self"
        cursor.getString(nameIndex) shouldNotBe null
    }

    @Test
    fun `queryChildDocuments for Device home enumerates storage locations`() = runTest {
        // Mock codec.encode() for root filesystem path
        val rootPath = LocalPath.build("/")
        coEvery { codec.encode(rootPath) } returns "local|Lw=="

        val cursor = handler.queryChildDocuments(
            ProviderLocation.Home.Device.documentId,
            null,
            null
        )

        // Phase 1: Should return at least root filesystem
        cursor.count shouldBeGreaterThan 0
        cursor.moveToFirst() shouldBe true

        val nameIndex = cursor.getColumnIndex(COLUMN_DISPLAY_NAME)
        cursor.getString(nameIndex) shouldNotBe null
    }

    @Test
    fun `queryChildDocuments for real directory lists children`() = runTest {
        val parentPath = LocalPath.build("/test")
        val parentDocId = "local|parent"

        val child1Path = LocalPath.build("/test/file1.txt")
        val child2Path = LocalPath.build("/test/file2.txt")

        val mockChild1 = mockk<APathLookup<APath<*>>> {
            coEvery { lookedUp } returns child1Path
            coEvery { name } returns "file1.txt"
            coEvery { fileType } returns FileType.FILE
            coEvery { size } returns 100L
            coEvery { modifiedAt } returns null
        }

        val mockChild2 = mockk<APathLookup<APath<*>>> {
            coEvery { lookedUp } returns child2Path
            coEvery { name } returns "file2.txt"
            coEvery { fileType } returns FileType.FILE
            coEvery { size } returns 200L
            coEvery { modifiedAt } returns null
        }

        coEvery { codec.decode(parentDocId) } returns parentPath
        coEvery { gatewaySwitch.lookupFiles(parentPath, any()) } returns listOf(mockChild1, mockChild2)
        coEvery { codec.encode(child1Path) } returns "local|child1"
        coEvery { codec.encode(child2Path) } returns "local|child2"

        val cursor = handler.queryChildDocuments(parentDocId, null, null)

        cursor.count shouldBe 2

        cursor.moveToFirst() shouldBe true
        val nameIndex = cursor.getColumnIndex(COLUMN_DISPLAY_NAME)
        cursor.getString(nameIndex) shouldBe "file1.txt"

        cursor.moveToNext() shouldBe true
        cursor.getString(nameIndex) shouldBe "file2.txt"
    }

    @Test
    fun `queryDocument handles exceptions gracefully`() = runTest {
        val documentId = "local|invalid"

        coEvery { codec.decode(documentId) } throws IllegalArgumentException("Invalid ID")

        val cursor = handler.queryDocument(documentId, null)

        // Should return empty cursor on error
        cursor.count shouldBe 0
    }

    @Test
    fun `queryChildDocuments handles exceptions gracefully`() = runTest {
        val parentDocId = "local|invalid"

        coEvery { codec.decode(parentDocId) } throws IllegalArgumentException("Invalid ID")

        val cursor = handler.queryChildDocuments(parentDocId, null, null)

        // Should return empty cursor on error
        cursor.count shouldBe 0
    }

    @Test
    fun `queryDocument with custom projection returns only requested columns`() = runTest {
        val projection = arrayOf(COLUMN_DOCUMENT_ID, COLUMN_DISPLAY_NAME)

        val cursor = handler.queryDocument(ProviderLocation.Root.Butler.rootDocumentId, projection)

        cursor.columnNames shouldBe projection
    }

    @Test
    fun `queryDocument for directory has DIR mime type`() = runTest {
        val path = LocalPath.build("/test/dir")
        val documentId = "local|dirtest"

        val mockLookup = mockk<APathLookup<APath<*>>> {
            coEvery { lookedUp } returns path
            coEvery { name } returns "dir"
            coEvery { fileType } returns FileType.DIRECTORY
            coEvery { size } returns null
            coEvery { modifiedAt } returns null
        }

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.lookup(path, any()) } returns mockLookup

        val cursor = handler.queryDocument(documentId, null)

        cursor.moveToFirst() shouldBe true
        val mimeIndex = cursor.getColumnIndex(COLUMN_MIME_TYPE)
        cursor.getString(mimeIndex) shouldBe MIME_TYPE_DIR
    }
}

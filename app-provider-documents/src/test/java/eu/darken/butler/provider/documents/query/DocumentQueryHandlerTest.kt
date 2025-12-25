package eu.darken.butler.provider.documents.query

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.*
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.provider.documents.core.ButlerDocumentsProvider
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import eu.darken.butler.provider.documents.core.ProviderLocation
import eu.darken.butler.provider.documents.core.query.DocumentQueryHandler
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentQueryHandlerTest {

    private lateinit var context: Context
    private lateinit var codec: DocumentIdCodec
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var storageManager2: StorageManager2
    private lateinit var safLocationManager: SAFLocationManager
    private lateinit var pathPermissionCheck: PathPermissionCheck
    private lateinit var handler: DocumentQueryHandler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        codec = mockk()
        gatewaySwitch = mockk()
        storageManager2 = mockk {
            every { storageVolumes } returns emptyList()
        }
        safLocationManager = mockk {
            every { locations } returns flowOf(emptyList())
        }
        pathPermissionCheck = mockk {
            // Default: all paths are accessible (no permission requirements)
            coEvery { monitor(any()) } returns flowOf(PathRequirements())
        }

        // Mock ButlerDocumentsProvider.AUTHORITY to avoid BuildConfig initialization issues
        mockkObject(ButlerDocumentsProvider.Companion)
        every { ButlerDocumentsProvider.AUTHORITY } returns "eu.darken.butler.test.documents"

        // Mock DocumentsContract.buildChildDocumentsUri
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.buildChildDocumentsUri(any(), any()) } answers {
            val authority = args[0] as String
            val documentId = args[1] as String
            mockk<Uri>(relaxed = true).also { uri ->
                every { uri.toString() } returns "content://$authority/document/$documentId/children"
            }
        }

        // Mock MimeTypeMap for tests since it doesn't work properly in Robolectric
        mockkStatic(MimeTypeMap::class)
        val mockMimeTypeMap = mockk<MimeTypeMap>(relaxed = true)
        every { MimeTypeMap.getSingleton() } returns mockMimeTypeMap
        every { mockMimeTypeMap.getMimeTypeFromExtension("pdf") } returns "application/pdf"
        every { mockMimeTypeMap.getMimeTypeFromExtension("jpg") } returns "image/jpeg"
        every { mockMimeTypeMap.getMimeTypeFromExtension("jpeg") } returns "image/jpeg"
        every { mockMimeTypeMap.getMimeTypeFromExtension("png") } returns "image/png"
        every { mockMimeTypeMap.getMimeTypeFromExtension("mp4") } returns "video/mp4"
        every { mockMimeTypeMap.getMimeTypeFromExtension("zip") } returns "application/zip"
        every { mockMimeTypeMap.getMimeTypeFromExtension("txt") } returns "text/plain"
        every { mockMimeTypeMap.getMimeTypeFromExtension("") } returns null

        handler = DocumentQueryHandler(
            context,
            codec,
            gatewaySwitch,
            storageManager2,
            safLocationManager,
            pathPermissionCheck
        )
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

        // Should return error cursor with generic error message
        cursor.count shouldBe 0
        cursor.extras shouldNotBe null
        cursor.extras.getString(DocumentsContract.EXTRA_ERROR) shouldNotBe null
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

    @Test
    fun `queryChildDocuments returns error cursor for inaccessible paths`() = runTest {
        val parentPath = LocalPath.build("/test")
        val parentDocId = "local|parent"

        val requiresRoot = eu.darken.butler.permissions.core.PathRequirements(
            combos = setOf(setOf(eu.darken.butler.setup.core.SetupModule.Type.ROOT))
        )

        coEvery { codec.decode(parentDocId) } returns parentPath
        coEvery { pathPermissionCheck.monitor(parentPath) } returns flowOf(requiresRoot)

        val cursor = handler.queryChildDocuments(parentDocId, null, null)

        // Should return empty cursor with EXTRA_ERROR
        cursor.count shouldBe 0
        cursor.extras shouldNotBe null
        cursor.extras.getString(DocumentsContract.EXTRA_ERROR) shouldNotBe null
    }

    @Test
    fun `enumerateStorageLocations filters out root filesystem when inaccessible`() = runTest {
        val rootPath = LocalPath.build("/")
        val requiresRoot = eu.darken.butler.permissions.core.PathRequirements(
            combos = setOf(setOf(eu.darken.butler.setup.core.SetupModule.Type.ROOT))
        )

        coEvery { pathPermissionCheck.monitor(rootPath) } returns flowOf(requiresRoot)
        coEvery { codec.encode(rootPath) } returns "local|Lw=="

        val cursor = handler.queryChildDocuments(
            ProviderLocation.Home.Device.documentId,
            null,
            null
        )

        // Should return empty cursor (root filesystem filtered, no other storage)
        cursor.count shouldBe 0
    }

    @Test
    fun `error cursor contains root access message for ROOT requirement`() = runTest {
        val parentPath = LocalPath.build("/system")
        val parentDocId = "local|system"

        val requiresRoot = eu.darken.butler.permissions.core.PathRequirements(
            combos = setOf(setOf(eu.darken.butler.setup.core.SetupModule.Type.ROOT))
        )

        coEvery { codec.decode(parentDocId) } returns parentPath
        coEvery { pathPermissionCheck.monitor(parentPath) } returns flowOf(requiresRoot)

        val cursor = handler.queryChildDocuments(parentDocId, null, null)

        cursor.count shouldBe 0
        val errorMessage = cursor.extras.getString(DocumentsContract.EXTRA_ERROR)
        errorMessage shouldNotBe null
        errorMessage!!.contains("Root access", ignoreCase = true) shouldBe true
    }

    @Test
    fun `error cursor contains ADB message for SHIZUKU requirement`() = runTest {
        val parentPath = LocalPath.build("/data")
        val parentDocId = "local|data"

        val requiresShizuku = eu.darken.butler.permissions.core.PathRequirements(
            combos = setOf(setOf(eu.darken.butler.setup.core.SetupModule.Type.SHIZUKU))
        )

        coEvery { codec.decode(parentDocId) } returns parentPath
        coEvery { pathPermissionCheck.monitor(parentPath) } returns flowOf(requiresShizuku)

        val cursor = handler.queryChildDocuments(parentDocId, null, null)

        cursor.count shouldBe 0
        val errorMessage = cursor.extras.getString(DocumentsContract.EXTRA_ERROR)
        errorMessage shouldNotBe null
        errorMessage!!.contains("ADB", ignoreCase = true) shouldBe true
    }

    @Test
    fun `error cursor contains storage permission message for STORAGE requirement`() = runTest {
        val parentPath = LocalPath.build("/storage/emulated/0")
        val parentDocId = "local|storage"

        val requiresStorage = eu.darken.butler.permissions.core.PathRequirements(
            combos = setOf(setOf(eu.darken.butler.setup.core.SetupModule.Type.STORAGE))
        )

        coEvery { codec.decode(parentDocId) } returns parentPath
        coEvery { pathPermissionCheck.monitor(parentPath) } returns flowOf(requiresStorage)

        val cursor = handler.queryChildDocuments(parentDocId, null, null)

        cursor.count shouldBe 0
        val errorMessage = cursor.extras.getString(DocumentsContract.EXTRA_ERROR)
        errorMessage shouldNotBe null
        errorMessage!!.contains("Storage permission", ignoreCase = true) shouldBe true
    }

    @Test
    fun `queryDocument detects MIME types from file extensions`() = runTest {
        val testFiles = mapOf(
            "document.pdf" to "application/pdf",
            "image.jpg" to "image/jpeg",
            "video.mp4" to "video/mp4",
            "archive.zip" to "application/zip",
            "text.txt" to "text/plain",
            "noextension" to "application/octet-stream",
        )

        testFiles.forEach { (filename, expectedMimeType) ->
            val path = LocalPath.build("/test/$filename")
            val documentId = "local|test"

            val mockLookup = mockk<APathLookup<APath<*>>> {
                coEvery { lookedUp } returns path
                coEvery { name } returns filename
                coEvery { fileType } returns FileType.FILE
                coEvery { size } returns 1024L
                coEvery { modifiedAt } returns Instant.fromEpochMilliseconds(1000000)
            }

            coEvery { codec.decode(documentId) } returns path
            coEvery { gatewaySwitch.lookup(path, any()) } returns mockLookup

            val cursor = handler.queryDocument(documentId, null)

            cursor.count shouldBe 1
            cursor.moveToFirst() shouldBe true

            val mimeIndex = cursor.getColumnIndex(COLUMN_MIME_TYPE)
            cursor.getString(mimeIndex) shouldBe expectedMimeType
        }
    }

    @Test
    fun `queryDocument resolves symlink to directory`() = runTest {
        val symlinkPath = LocalPath.build("/test/link")
        val targetPath = LocalPath.build("/test/target")
        val documentId = "local|symlink"

        val targetLookup = mockk<APathLookup<APath<*>>> {
            coEvery { lookedUp } returns targetPath
            coEvery { name } returns "target"
            coEvery { fileType } returns FileType.DIRECTORY
        }

        val symlinkLookup = mockk<APathLookup<APath<*>>> {
            coEvery { lookedUp } returns symlinkPath
            coEvery { name } returns "link"
            coEvery { fileType } returns FileType.SYMBOLIC_LINK
            coEvery { target } returns targetPath
            coEvery { size } returns null
            coEvery { modifiedAt } returns Instant.fromEpochMilliseconds(1000000)
        }

        coEvery { codec.decode(documentId) } returns symlinkPath
        coEvery { gatewaySwitch.lookup(symlinkPath, any()) } returns symlinkLookup
        coEvery { gatewaySwitch.lookup(targetPath, any()) } returns targetLookup

        val cursor = handler.queryDocument(documentId, null)

        cursor.count shouldBe 1
        cursor.moveToFirst() shouldBe true

        val mimeIndex = cursor.getColumnIndex(COLUMN_MIME_TYPE)
        cursor.getString(mimeIndex) shouldBe MIME_TYPE_DIR
    }

    @Test
    fun `queryDocument resolves symlink to file`() = runTest {
        val symlinkPath = LocalPath.build("/test/link.txt")
        val targetPath = LocalPath.build("/test/target.pdf")
        val documentId = "local|symlink"

        val targetLookup = mockk<APathLookup<APath<*>>> {
            coEvery { lookedUp } returns targetPath
            coEvery { name } returns "target.pdf"
            coEvery { fileType } returns FileType.FILE
        }

        val symlinkLookup = mockk<APathLookup<APath<*>>> {
            coEvery { lookedUp } returns symlinkPath
            coEvery { name } returns "link.txt"
            coEvery { fileType } returns FileType.SYMBOLIC_LINK
            coEvery { target } returns targetPath
            coEvery { size } returns null
            coEvery { modifiedAt } returns Instant.fromEpochMilliseconds(1000000)
        }

        coEvery { codec.decode(documentId) } returns symlinkPath
        coEvery { gatewaySwitch.lookup(symlinkPath, any()) } returns symlinkLookup
        coEvery { gatewaySwitch.lookup(targetPath, any()) } returns targetLookup

        val cursor = handler.queryDocument(documentId, null)

        cursor.count shouldBe 1
        cursor.moveToFirst() shouldBe true

        val mimeIndex = cursor.getColumnIndex(COLUMN_MIME_TYPE)
        // Should use target's MIME type (application/pdf) not symlink's name (.txt)
        cursor.getString(mimeIndex) shouldBe "application/pdf"
    }

    @Test
    fun `queryDocument handles broken symlink gracefully`() = runTest {
        val symlinkPath = LocalPath.build("/test/broken-link.txt")
        val documentId = "local|symlink"

        val symlinkLookup = mockk<APathLookup<APath<*>>> {
            coEvery { lookedUp } returns symlinkPath
            coEvery { name } returns "broken-link.txt"
            coEvery { fileType } returns FileType.SYMBOLIC_LINK
            coEvery { target } returns null // Broken symlink has no target
            coEvery { size } returns null
            coEvery { modifiedAt } returns Instant.fromEpochMilliseconds(1000000)
        }

        coEvery { codec.decode(documentId) } returns symlinkPath
        coEvery { gatewaySwitch.lookup(symlinkPath, any()) } returns symlinkLookup

        val cursor = handler.queryDocument(documentId, null)

        cursor.count shouldBe 1
        cursor.moveToFirst() shouldBe true

        val mimeIndex = cursor.getColumnIndex(COLUMN_MIME_TYPE)
        // Should fall back to using symlink's name
        cursor.getString(mimeIndex) shouldBe "text/plain"
    }
}

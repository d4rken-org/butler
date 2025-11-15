package eu.darken.butler.provider.documents.reader

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import eu.darken.butler.provider.documents.core.reader.DocumentReader
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileInputStream
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DocumentReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var codec: DocumentIdCodec
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var reader: DocumentReader

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        codec = mockk()
        gatewaySwitch = mockk()
        reader = DocumentReader(context, codec, gatewaySwitch)
    }

    @Test
    fun `openDocument returns readable ParcelFileDescriptor for LocalPath`() = runTest {
        // Given: Create test file
        val testFile = tempFolder.newFile("test.txt")
        val testContent = "Hello DocumentsProvider"
        testFile.writeText(testContent)

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|encoded"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.openInputStream(path) } returns testContent.byteInputStream()

        // When
        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine to transfer data through pipe
        kotlinx.coroutines.delay(1000)

        // Then: Can read file contents
        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes().toString(Charsets.UTF_8)
            content shouldBe testContent
        }

        pfd.close()
    }

    @Test
    fun `openDocument throws FileNotFoundException for missing file`() = runTest {
        val path = LocalPath.build("/nonexistent/missing.txt")
        val documentId = "local|missing"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.openInputStream(path) } throws java.io.FileNotFoundException("File not found")

        try {
            reader.openDocument(documentId, "r", null)
            throw AssertionError("Should have thrown FileNotFoundException")
        } catch (e: FileNotFoundException) {
            e.message shouldContain "File not found"
        }
    }

    @Test
    fun `openDocument throws FileNotFoundException for directory`() = runTest {
        // Given: Directory instead of file
        val testDir = tempFolder.newFolder("testdir")
        val path = LocalPath.build(testDir.absolutePath)
        val documentId = "local|dir"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.openInputStream(path) } throws java.io.FileNotFoundException("Not a file")

        try {
            reader.openDocument(documentId, "r", null)
            throw AssertionError("Should have thrown FileNotFoundException")
        } catch (e: FileNotFoundException) {
            e.message shouldContain "Not a file"
        }
    }

    @Test
    fun `openDocument respects CancellationSignal`() = runTest {
        val testFile = tempFolder.newFile("test.txt")
        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|encoded"

        coEvery { codec.decode(documentId) } returns path

        val signal = CancellationSignal().apply { cancel() }

        try {
            reader.openDocument(documentId, "r", signal)
            throw AssertionError("Should have thrown OperationCanceledException")
        } catch (e: OperationCanceledException) {
            // Expected
        }
    }

    @Test
    fun `openDocument throws FileNotFoundException for invalid document ID`() = runTest {
        val documentId = "invalid|data"

        coEvery { codec.decode(documentId) } throws IllegalArgumentException("Invalid document ID")

        try {
            reader.openDocument(documentId, "r", null)
            throw AssertionError("Should have thrown FileNotFoundException")
        } catch (e: FileNotFoundException) {
            e.message shouldContain "Cannot open document"
        }
    }

    @Test
    fun `openDocument throws FileNotFoundException for virtual document`() = runTest {
        val documentId = "butler"

        coEvery { codec.decode(documentId) } throws IllegalArgumentException("Virtual document")

        try {
            reader.openDocument(documentId, "r", null)
            throw AssertionError("Should have thrown FileNotFoundException")
        } catch (e: FileNotFoundException) {
            e.message shouldContain "Cannot open document"
        }
    }

    @Test
    fun `openDocument handles SAFPath via pipe pattern`() = runTest {
        // Given: SAF path that will be opened via GatewaySwitch (pipe pattern)
        val androidUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AFolder/document/primary%3AFolder%2Ftest.txt")
        val safUri = mockk<SafUri> {
            every { toAndroidUri() } returns androidUri
        }
        val safPath = mockk<SAFPath> {
            every { pathUri } returns safUri
            every { path } returns "/tree/primary:Folder/test.txt"
        }
        val documentId = "saf|encoded"
        val testContent = "SAF file content via pipe"

        coEvery { codec.decode(documentId) } returns safPath
        coEvery { gatewaySwitch.openInputStream(safPath) } returns testContent.byteInputStream()

        // When
        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine to transfer data through pipe
        kotlinx.coroutines.delay(1000)

        // Then: Can read file contents through pipe
        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes().toString(Charsets.UTF_8)
            content shouldBe testContent
        }

        pfd.close()
    }

    @Test
    fun `openDocument uses pipe for SAFPath with large file`() = runTest {
        // Given: Larger SAF file (10KB) - tests pipe streaming with more data
        val androidUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AFolder/document/primary%3AFolder%2Flarge.bin")
        val safUri = mockk<SafUri> {
            every { toAndroidUri() } returns androidUri
        }
        val safPath = mockk<SAFPath> {
            every { pathUri } returns safUri
            every { path } returns "/tree/primary:Folder/large.bin"
        }
        val documentId = "saf|large"
        val largeData = ByteArray(10 * 1024) { (it % 256).toByte() }

        coEvery { codec.decode(documentId) } returns safPath
        coEvery { gatewaySwitch.openInputStream(safPath) } returns largeData.inputStream()

        // When
        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine to transfer data through pipe
        kotlinx.coroutines.delay(1000)

        // Then: Can read all data through pipe
        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe largeData.size
            // Verify data integrity on sample points (not every byte for performance)
            content[0] shouldBe 0.toByte()
            content[255] shouldBe 255.toByte()
            content[256] shouldBe 0.toByte()
            content[largeData.size - 1] shouldBe ((largeData.size - 1) % 256).toByte()
        }

        pfd.close()
    }

    @Test
    fun `openDocument can read actual file content`() = runTest {
        // Given: File with specific content
        val testFile = tempFolder.newFile("data.txt")
        val testData = "Line 1\nLine 2\nLine 3"
        testFile.writeText(testData)

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|data"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.openInputStream(path) } returns testData.byteInputStream()

        // When
        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine to transfer data through pipe
        kotlinx.coroutines.delay(1000)

        // Then: Read line by line
        FileInputStream(pfd.fileDescriptor).bufferedReader().use { reader ->
            reader.readLine() shouldBe "Line 1"
            reader.readLine() shouldBe "Line 2"
            reader.readLine() shouldBe "Line 3"
        }

        pfd.close()
    }

    @Test
    fun `openDocument handles empty file`() = runTest {
        // Given: Empty file
        val testFile = tempFolder.newFile("empty.txt")
        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|empty"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.openInputStream(path) } returns ByteArray(0).inputStream()

        // When
        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine to transfer data through pipe
        kotlinx.coroutines.delay(1000)

        // Then: Can open and read (returns empty)
        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe 0
        }

        pfd.close()
    }

    @Test
    fun `openDocument handles large file`() = runTest {
        // Given: Large file (100KB) - reduced for faster testing
        val testFile = tempFolder.newFile("large.txt")
        val largeData = ByteArray(100 * 1024) { (it % 256).toByte() }
        testFile.writeBytes(largeData)

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|large"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.openInputStream(path) } returns largeData.inputStream()

        // When
        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine to transfer data through pipe
        kotlinx.coroutines.delay(1000)

        // Then: Can read all data
        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe largeData.size
        }

        pfd.close()
    }
}

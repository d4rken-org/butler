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
import kotlinx.coroutines.runBlocking
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
    fun `openDocument reads LocalPath via direct ParcelFileDescriptor`() = runBlocking {
        val testFile = tempFolder.newFile("test.txt")
        val testContent = "Direct local fd"
        testFile.writeText(testContent)

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|encoded"

        coEvery { codec.decode(documentId) } returns path

        val pfd = reader.openDocument(documentId, "r", null)

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
        coEvery { gatewaySwitch.file(path, false) } throws java.io.FileNotFoundException("File not found")
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
        val testDir = tempFolder.newFolder("testdir")
        val path = LocalPath.build(testDir.absolutePath)
        val documentId = "local|dir"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } throws java.io.FileNotFoundException("Not a file")
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
    fun `openDocument handles SAFPath via pipe fallback`() = runBlocking {
        val androidUri =
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AFolder/document/primary%3AFolder%2Ftest.txt")
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
        coEvery { gatewaySwitch.file(safPath, false) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openInputStream(safPath) } returns testContent.byteInputStream()

        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine (Dispatchers.IO) to transfer data through pipe
        Thread.sleep(1000)

        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes().toString(Charsets.UTF_8)
            content shouldBe testContent
        }

        pfd.close()
    }

    @Test
    fun `openDocument uses pipe for SAFPath with large file`() = runBlocking {
        val androidUri =
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AFolder/document/primary%3AFolder%2Flarge.bin")
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
        coEvery { gatewaySwitch.file(safPath, false) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openInputStream(safPath) } returns largeData.inputStream()

        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine (Dispatchers.IO) to transfer data through pipe
        Thread.sleep(1000)

        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe largeData.size
            content[0] shouldBe 0.toByte()
            content[255] shouldBe 255.toByte()
            content[256] shouldBe 0.toByte()
            content[largeData.size - 1] shouldBe ((largeData.size - 1) % 256).toByte()
        }

        pfd.close()
    }

    @Test
    fun `openDocument reads file content via pipe fallback`() = runBlocking {
        val testFile = tempFolder.newFile("data.txt")
        val testData = "Line 1\nLine 2\nLine 3"
        testFile.writeText(testData)

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|data"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openInputStream(path) } returns testData.byteInputStream()

        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine (Dispatchers.IO) to transfer data through pipe
        Thread.sleep(1000)

        FileInputStream(pfd.fileDescriptor).bufferedReader().use { reader ->
            reader.readLine() shouldBe "Line 1"
            reader.readLine() shouldBe "Line 2"
            reader.readLine() shouldBe "Line 3"
        }

        pfd.close()
    }

    @Test
    fun `openDocument handles empty file via pipe fallback`() = runBlocking {
        val testFile = tempFolder.newFile("empty.txt")
        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|empty"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openInputStream(path) } returns ByteArray(0).inputStream()

        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine (Dispatchers.IO) to transfer data through pipe
        Thread.sleep(1000)

        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe 0
        }

        pfd.close()
    }

    @Test
    fun `openDocument handles large file via pipe fallback`() = runBlocking {
        val largeData = ByteArray(100 * 1024) { (it % 256).toByte() }

        val testFile = tempFolder.newFile("large.txt")
        testFile.writeBytes(largeData)

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|large"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openInputStream(path) } returns largeData.inputStream()

        val pfd = reader.openDocument(documentId, "r", null)

        // Allow background coroutine (Dispatchers.IO) to transfer data through pipe
        Thread.sleep(1000)

        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe largeData.size
        }

        pfd.close()
    }
}

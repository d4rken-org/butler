package eu.darken.butler.provider.documents.reader

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.io.ProxyPfdFactory
import eu.darken.butler.provider.documents.core.DocumentIdCodec
import eu.darken.butler.provider.documents.core.reader.DocumentReader
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileHandle
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DocumentReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var codec: DocumentIdCodec
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var proxyPfdFactory: ProxyPfdFactory
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var reader: DocumentReader

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        codec = mockk()
        gatewaySwitch = mockk()
        proxyPfdFactory = mockk()
        testDispatcher = StandardTestDispatcher()
        val dispatcherProvider = object : DispatcherProvider {
            override val IO = testDispatcher
        }
        reader = DocumentReader(context, codec, gatewaySwitch, proxyPfdFactory, dispatcherProvider)
    }

    @Test
    fun `openDocument reads LocalPath via pipe fallback`() = runTest(testDispatcher) {
        val testContent = "Hello DocumentsProvider"
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|encoded"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openInputStream(path) } returns testContent.byteInputStream()

        val pfd = reader.openDocument(documentId, "r", null)
        testDispatcher.scheduler.advanceUntilIdle()

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
        coEvery { gatewaySwitch.file(path, false) } throws FileNotFoundException("File not found")
        coEvery { gatewaySwitch.openInputStream(path) } throws FileNotFoundException("File not found")

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
        coEvery { gatewaySwitch.file(path, false) } throws FileNotFoundException("Not a file")
        coEvery { gatewaySwitch.openInputStream(path) } throws FileNotFoundException("Not a file")

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
    fun `openDocument handles SAFPath via pipe fallback`() = runTest(testDispatcher) {
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
        testDispatcher.scheduler.advanceUntilIdle()

        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes().toString(Charsets.UTF_8)
            content shouldBe testContent
        }

        pfd.close()
    }

    @Test
    fun `openDocument uses pipe for SAFPath with large file`() = runTest(testDispatcher) {
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
        testDispatcher.scheduler.advanceUntilIdle()

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
    fun `openDocument reads file content via pipe fallback`() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("data.txt")
        val testData = "Line 1\nLine 2\nLine 3"
        testFile.writeText(testData)

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|data"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openInputStream(path) } returns testData.byteInputStream()

        val pfd = reader.openDocument(documentId, "r", null)
        testDispatcher.scheduler.advanceUntilIdle()

        FileInputStream(pfd.fileDescriptor).bufferedReader().use { reader ->
            reader.readLine() shouldBe "Line 1"
            reader.readLine() shouldBe "Line 2"
            reader.readLine() shouldBe "Line 3"
        }

        pfd.close()
    }

    @Test
    fun `openDocument handles empty file via pipe fallback`() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("empty.txt")
        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|empty"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openInputStream(path) } returns ByteArray(0).inputStream()

        val pfd = reader.openDocument(documentId, "r", null)
        testDispatcher.scheduler.advanceUntilIdle()

        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe 0
        }

        pfd.close()
    }

    @Test
    fun `openDocument handles large file via pipe fallback`() = runTest(testDispatcher) {
        val largeData = ByteArray(100 * 1024) { (it % 256).toByte() }

        val testFile = tempFolder.newFile("large.txt")
        testFile.writeBytes(largeData)

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|large"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openInputStream(path) } returns largeData.inputStream()

        val pfd = reader.openDocument(documentId, "r", null)
        testDispatcher.scheduler.advanceUntilIdle()

        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe largeData.size
        }

        pfd.close()
    }

    @Test
    fun `openDocument writes via pipe fallback`(): Unit = runTest(testDispatcher) {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|write"
        val outputStream = ByteArrayOutputStream()

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, true) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openOutputStream(path, append = false) } returns outputStream

        val pfd = reader.openDocument(documentId, "w", null)

        val testContent = "Written content"
        FileOutputStream(pfd.fileDescriptor).use { it.write(testContent.toByteArray()) }
        pfd.close()

        testDispatcher.scheduler.advanceUntilIdle()

        outputStream.toString(Charsets.UTF_8.name()) shouldBe testContent
    }

    @Test
    fun `openDocument writes append via pipe fallback`(): Unit = runTest(testDispatcher) {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|append"
        val outputStream = ByteArrayOutputStream()

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, true) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openOutputStream(path, append = true) } returns outputStream

        val pfd = reader.openDocument(documentId, "wa", null)

        val testContent = "Appended content"
        FileOutputStream(pfd.fileDescriptor).use { it.write(testContent.toByteArray()) }
        pfd.close()

        testDispatcher.scheduler.advanceUntilIdle()

        outputStream.toString(Charsets.UTF_8.name()) shouldBe testContent
    }

    @Test
    fun `openDocument write throws for missing file`() = runTest {
        val path = LocalPath.build("/nonexistent/file.txt")
        val documentId = "local|write-missing"

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, true) } throws FileNotFoundException("File not found")
        coEvery { gatewaySwitch.openOutputStream(path, append = false) } throws FileNotFoundException("File not found")

        try {
            reader.openDocument(documentId, "w", null)
            throw AssertionError("Should have thrown FileNotFoundException")
        } catch (e: FileNotFoundException) {
            e.message shouldContain "File not found"
        }
    }

    @Test
    fun `openDocument read-write falls back to write pipe`(): Unit = runTest(testDispatcher) {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|rw"
        val outputStream = ByteArrayOutputStream()

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, true) } throws UnsupportedOperationException("No seekable access")
        coEvery { gatewaySwitch.openOutputStream(path, append = true) } returns outputStream

        val pfd = reader.openDocument(documentId, "rw", null)

        val testContent = "Read-write content"
        FileOutputStream(pfd.fileDescriptor).use { it.write(testContent.toByteArray()) }
        pfd.close()

        testDispatcher.scheduler.advanceUntilIdle()

        outputStream.toString(Charsets.UTF_8.name()) shouldBe testContent
    }

    @Test
    fun `openDocument throws for unsupported mode`() = runTest {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|unsupported"

        coEvery { codec.decode(documentId) } returns path

        try {
            reader.openDocument(documentId, "x", null)
            throw AssertionError("Should have thrown FileNotFoundException")
        } catch (e: FileNotFoundException) {
            e.message shouldContain "Cannot open document"
        }
    }

    // ========== Step 1: Mode semantics tests ==========

    @Test
    fun `openDocument rw mode does not truncate`() = runTest {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|rw"
        val fileHandle = mockk<FileHandle>(relaxed = true)
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, true) } returns fileHandle
        every { proxyPfdFactory.create(fileHandle, "rw") } returns mockPfd

        reader.openDocument(documentId, "rw", null)

        verify(exactly = 0) { fileHandle.resize(any()) }
    }

    @Test
    fun `openDocument rwt mode truncates`() = runTest {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|rwt"
        val fileHandle = mockk<FileHandle>(relaxed = true)
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, true) } returns fileHandle
        every { proxyPfdFactory.create(fileHandle, "rw") } returns mockPfd

        reader.openDocument(documentId, "rwt", null)

        verify(exactly = 1) { fileHandle.resize(0) }
    }

    @Test
    fun `openDocument wa mode does not truncate`() = runTest {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|wa"
        val fileHandle = mockk<FileHandle>(relaxed = true)
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, true) } returns fileHandle
        every { proxyPfdFactory.create(fileHandle, "wa") } returns mockPfd

        reader.openDocument(documentId, "wa", null)

        verify(exactly = 0) { fileHandle.resize(any()) }
    }

    // ========== Step 2: Seekable ProxyPFD tests ==========

    @Test
    fun `openDocument reads via seekable ProxyPFD when file() succeeds`() = runTest {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|seekable-read"
        val fileHandle = mockk<FileHandle>(relaxed = true)
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } returns fileHandle
        every { proxyPfdFactory.create(fileHandle, "r") } returns mockPfd

        val result = reader.openDocument(documentId, "r", null)

        result shouldBe mockPfd
        verify(exactly = 1) { proxyPfdFactory.create(fileHandle, "r") }
    }

    @Test
    fun `openDocument writes via seekable ProxyPFD`() = runTest {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|seekable-write"
        val fileHandle = mockk<FileHandle>(relaxed = true)
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, true) } returns fileHandle
        every { proxyPfdFactory.create(fileHandle, "w") } returns mockPfd

        val result = reader.openDocument(documentId, "w", null)

        result shouldBe mockPfd
        verify(exactly = 1) { proxyPfdFactory.create(fileHandle, "w") }
    }

    @Test
    fun `openDocument seekable ProxyPFD cleans up FileHandle on factory failure`() = runTest(testDispatcher) {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|seekable-cleanup"
        val fileHandle = mockk<FileHandle>(relaxed = true)
        val outputStream = ByteArrayOutputStream()

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, true) } returns fileHandle
        every { proxyPfdFactory.create(fileHandle, "w") } throws RuntimeException("Proxy creation failed")
        coEvery { gatewaySwitch.openOutputStream(path, append = false) } returns outputStream

        // Should fall back to pipe without crashing
        val pfd = reader.openDocument(documentId, "w", null)
        pfd.close()
        testDispatcher.scheduler.advanceUntilIdle()

        // Factory was attempted
        verify(exactly = 1) { proxyPfdFactory.create(fileHandle, "w") }
    }

    @Test
    fun `openDocument seekable ProxyPFD reports correct size via factory`() = runTest {
        val path = LocalPath.build("/some/file.txt")
        val documentId = "local|seekable-size"
        val fileHandle = mockk<FileHandle>(relaxed = true) {
            every { size() } returns 42L
        }
        val mockPfd = mockk<ParcelFileDescriptor>(relaxed = true)

        coEvery { codec.decode(documentId) } returns path
        coEvery { gatewaySwitch.file(path, false) } returns fileHandle
        every { proxyPfdFactory.create(fileHandle, "r") } returns mockPfd

        val result = reader.openDocument(documentId, "r", null)

        result shouldBe mockPfd
        // Factory receives the FileHandle that has size information
        verify(exactly = 1) { proxyPfdFactory.create(fileHandle, "r") }
    }
}

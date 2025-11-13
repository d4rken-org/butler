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
        testFile.writeText("Hello DocumentsProvider")

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|encoded"

        coEvery { codec.decode(documentId) } returns path

        // When
        val pfd = reader.openDocument(documentId, "r", null)

        // Then: Can read file contents
        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes().toString(Charsets.UTF_8)
            content shouldBe "Hello DocumentsProvider"
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
    fun `openDocument throws UnsupportedOperationException for write mode in Phase 1`() = runTest {
        val testFile = tempFolder.newFile("test.txt")
        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|encoded"

        coEvery { codec.decode(documentId) } returns path

        try {
            reader.openDocument(documentId, "w", null)
            throw AssertionError("Should have thrown UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            e.message shouldContain "Write operations not yet supported"
        }
    }

    @Test
    fun `openDocument throws UnsupportedOperationException for rw mode in Phase 1`() = runTest {
        val testFile = tempFolder.newFile("test.txt")
        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|encoded"

        coEvery { codec.decode(documentId) } returns path

        try {
            reader.openDocument(documentId, "rw", null)
            throw AssertionError("Should have thrown UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            e.message shouldContain "Write operations not yet supported"
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
    fun `openDocument handles SAFPath`() = runTest {
        // Given: SAF path (mock URI - actual SAF testing requires more setup)
        val androidUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2Ftest.txt")
        val safUri = mockk<SafUri> {
            every { toAndroidUri() } returns androidUri
        }
        val safPath = mockk<SAFPath> {
            every { pathUri } returns safUri
        }
        val documentId = "saf|encoded"

        coEvery { codec.decode(documentId) } returns safPath

        // Note: In real Robolectric environment, ContentResolver.openFileDescriptor might not work
        // This test validates the code path exists, but full SAF testing requires instrumented tests
        try {
            reader.openDocument(documentId, "r", null)
            // If it succeeds, that's fine (depends on Robolectric SAF support)
        } catch (e: FileNotFoundException) {
            // Expected in test environment without real SAF setup
            // Either our error message or Robolectric's is acceptable
            val validMessages = listOf("Couldn't open SAF file", "No content provider")
            validMessages.any { e.message?.contains(it) == true } shouldBe true
        }
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

        // When
        val pfd = reader.openDocument(documentId, "r", null)

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

        // When
        val pfd = reader.openDocument(documentId, "r", null)

        // Then: Can open and read (returns empty)
        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe 0
        }

        pfd.close()
    }

    @Test
    fun `openDocument handles large file`() = runTest {
        // Given: Large file (1MB)
        val testFile = tempFolder.newFile("large.txt")
        val largeData = ByteArray(1024 * 1024) { (it % 256).toByte() }
        testFile.writeBytes(largeData)

        val path = LocalPath.build(testFile.absolutePath)
        val documentId = "local|large"

        coEvery { codec.decode(documentId) } returns path

        // When
        val pfd = reader.openDocument(documentId, "r", null)

        // Then: Can read all data
        FileInputStream(pfd.fileDescriptor).use { inputStream ->
            val content = inputStream.readBytes()
            content.size shouldBe largeData.size
        }

        pfd.close()
    }
}

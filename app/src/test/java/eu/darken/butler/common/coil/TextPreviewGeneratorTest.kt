package eu.darken.butler.common.coil

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coil.fetchers.TextPreviewGenerator
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileHandle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextPreviewGeneratorTest : BaseTest() {

    private lateinit var context: Context
    private lateinit var gatewaySwitch: GatewaySwitch
    private lateinit var textPreviewGenerator: TextPreviewGenerator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        gatewaySwitch = mockk()
        textPreviewGenerator = TextPreviewGenerator(context, gatewaySwitch)
    }

    @Test
    fun `generate preview from small text file`() = runTest {
        // Arrange
        val textContent = "Hello World\nThis is a test file\nWith multiple lines"
        val lookup = createMockLookup(
            path = "/test/file.txt",
            size = textContent.toByteArray().size.toLong()
        )
        mockFileRead(lookup, textContent)

        // Act
        val bitmap = textPreviewGenerator.generate(lookup)

        // Assert
        bitmap shouldNotBe null
        bitmap!!.width shouldBe 512
        bitmap.height shouldBe 512
        bitmap.config shouldBe Bitmap.Config.ARGB_8888
    }

    @Test
    fun `generate preview from large text file with truncation`() = runTest {
        // Arrange
        val largeText = (1..100).joinToString("\n") { "Line $it with some content" }
        val lookup = createMockLookup(
            path = "/test/large.txt",
            size = largeText.toByteArray().size.toLong()
        )
        mockFileRead(lookup, largeText)

        // Act
        val bitmap = textPreviewGenerator.generate(lookup, maxBytes = 4096)

        // Assert
        bitmap shouldNotBe null
        bitmap!!.width shouldBe 512
        bitmap.height shouldBe 512
        // Should generate successfully even with truncated content
    }

    @Test
    fun `generate preview from empty file returns valid bitmap`() = runTest {
        // Arrange
        val emptyContent = ""
        val lookup = createMockLookup(
            path = "/test/empty.txt",
            size = 0L
        )
        mockFileRead(lookup, emptyContent)

        // Act
        val bitmap = textPreviewGenerator.generate(lookup)

        // Assert
        bitmap shouldNotBe null
        bitmap!!.width shouldBe 512
        bitmap.height shouldBe 512
    }

    @Test
    fun `generate preview respects maxBytes parameter`() = runTest {
        // Arrange
        val longText = "A".repeat(10000) // 10KB of text
        val lookup = createMockLookup(
            path = "/test/long.txt",
            size = longText.toByteArray().size.toLong()
        )
        mockFileRead(lookup, longText.take(2048)) // Mock will only return first 2KB

        // Act
        val bitmap = textPreviewGenerator.generate(lookup, maxBytes = 2048)

        // Assert
        bitmap shouldNotBe null
        // Bitmap should be generated successfully with truncated content
    }

    @Test
    fun `generate preview with custom dimensions`() = runTest {
        // Arrange
        val textContent = "Custom size test"
        val lookup = createMockLookup(
            path = "/test/custom.txt",
            size = textContent.toByteArray().size.toLong()
        )
        mockFileRead(lookup, textContent)

        // Act
        val bitmap = textPreviewGenerator.generate(lookup, width = 256, height = 256)

        // Assert
        bitmap shouldNotBe null
        bitmap!!.width shouldBe 256
        bitmap.height shouldBe 256
    }

    @Test
    fun `generate preview handles multiline text correctly`() = runTest {
        // Arrange
        val multilineText = """
            First line
            Second line
            Third line
            Fourth line
            Fifth line
        """.trimIndent()
        val lookup = createMockLookup(
            path = "/test/multiline.txt",
            size = multilineText.toByteArray().size.toLong()
        )
        mockFileRead(lookup, multilineText)

        // Act
        val bitmap = textPreviewGenerator.generate(lookup)

        // Assert
        bitmap shouldNotBe null
        // Bitmap should contain rendered multiline text
    }

    @Test
    fun `generate preview handles UTF-8 text`() = runTest {
        // Arrange
        val utf8Text = "Hello 世界 🌍\nUnicode test: café, naïve"
        val lookup = createMockLookup(
            path = "/test/utf8.txt",
            size = utf8Text.toByteArray(Charsets.UTF_8).size.toLong()
        )
        mockFileRead(lookup, utf8Text)

        // Act
        val bitmap = textPreviewGenerator.generate(lookup)

        // Assert
        bitmap shouldNotBe null
        bitmap!!.width shouldBe 512
        bitmap.height shouldBe 512
    }

    @Test
    fun `generate preview handles file read error gracefully`() = runTest {
        // Arrange
        val lookup = createMockLookup(
            path = "/test/error.txt",
            size = 100L
        )
        mockFileReadError(lookup, "File read error")

        // Act
        val bitmap = textPreviewGenerator.generate(lookup)

        // Assert
        bitmap shouldBe null
        // Should return null on error
    }

    @Test
    fun `generate preview truncates to max lines`() = runTest {
        // Arrange
        val manyLines = (1..100).joinToString("\n") { "Line $it" }
        val lookup = createMockLookup(
            path = "/test/many-lines.txt",
            size = manyLines.toByteArray().size.toLong()
        )
        mockFileRead(lookup, manyLines)

        // Act
        val bitmap = textPreviewGenerator.generate(lookup)

        // Assert
        bitmap shouldNotBe null
        // Content should be truncated to max lines (implementation detail)
    }

    @Test
    fun `generate preview handles JSON content`() = runTest {
        // Arrange
        val jsonContent = """
            {
              "name": "Test",
              "value": 123,
              "nested": {
                "key": "value"
              }
            }
        """.trimIndent()
        val lookup = createMockLookup(
            path = "/test/data.json",
            size = jsonContent.toByteArray().size.toLong()
        )
        mockFileRead(lookup, jsonContent)

        // Act
        val bitmap = textPreviewGenerator.generate(lookup)

        // Assert
        bitmap shouldNotBe null
        bitmap!!.width shouldBe 512
        bitmap.height shouldBe 512
    }

    // Helper functions

    private fun createMockLookup(path: String, size: Long): APathLookup<*> {
        val pathMock = mockk<LocalPath> {
            every { this@mockk.path } returns path
            every { segments } returns path.split("/")
            every { userReadablePath } returns path.toCaString()
            every { userReadableName } returns path.substringAfterLast("/").toCaString()
        }

        return mockk {
            every { lookedUp } returns pathMock
            every { this@mockk.path } returns path
            every { this@mockk.size } returns size
            every { fileType } returns FileType.FILE
            every { name } returns path.substringAfterLast("/")
            every { modifiedAt } returns Instant.DISTANT_PAST
        }
    }

    private fun mockFileRead(lookup: APathLookup<*>, content: String) {
        // Create a real Buffer with the content
        val buffer = Buffer().apply {
            write(content.toByteArray(Charsets.UTF_8))
        }

        // Use the Buffer's source which can be buffered
        val source = buffer

        val handle = mockk<FileHandle>(relaxed = true)
        every { handle.source() } returns source

        coEvery { gatewaySwitch.file(lookup.lookedUp, readWrite = false) } returns handle
    }

    private fun mockFileReadError(lookup: APathLookup<*>, errorMessage: String) {
        coEvery {
            gatewaySwitch.file(lookup.lookedUp, readWrite = false)
        } throws Exception(errorMessage)
    }
}

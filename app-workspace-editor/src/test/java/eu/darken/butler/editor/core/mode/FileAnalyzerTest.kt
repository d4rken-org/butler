package eu.darken.butler.editor.core.mode

import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.instanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.uuid.Uuid

class FileAnalyzerTest {

    private val workspaceId = Workspace.Id(Uuid.random())
    private val analyzer = FileAnalyzer()

    @Test
    fun `detects text mode by extension - txt`() = runTest {
        // Given: A .txt file
        val dataSource = InMemoryDataSource(workspaceId, "Hello World")
        val testPath = mockPath("test.txt")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect TextMode
        mode shouldBe instanceOf<TextMode>()
    }

    @Test
    fun `detects text mode by extension - kotlin`() = runTest {
        // Given: A .kt file
        val dataSource = InMemoryDataSource(workspaceId, "fun main() {}")
        val testPath = mockPath("Main.kt")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect TextMode
        mode shouldBe instanceOf<TextMode>()
    }

    @Test
    fun `detects hex mode by extension - binary`() = runTest {
        // Given: A .bin file
        val dataSource = InMemoryDataSource(workspaceId, "data")
        val testPath = mockPath("data.exe")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect HexMode
        mode shouldBe instanceOf<HexMode>()
    }

    @Test
    fun `detects hex mode by extension - image`() = runTest {
        // Given: A .png file
        val dataSource = InMemoryDataSource(workspaceId, "")
        val testPath = mockPath("image.png")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect HexMode
        mode shouldBe instanceOf<HexMode>()
    }

    @Test
    fun `detects text mode by content - plain text`() = runTest {
        // Given: Unknown extension but text content
        val textContent = "This is plain text content\nWith multiple lines\nAnd normal ASCII characters"
        val dataSource = InMemoryDataSource(workspaceId, textContent)
        val testPath = mockPath("unknown.xyz")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect TextMode based on content
        mode shouldBe instanceOf<TextMode>()
    }

    @Test
    fun `detects hex mode by content - null bytes`() = runTest {
        // Given: Unknown extension with binary content (null bytes)
        val binaryContent = "Some text\u0000with null\u0000bytes"
        val dataSource = InMemoryDataSource(workspaceId, binaryContent)
        val testPath = mockPath("unknown.data")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect HexMode based on null bytes
        mode shouldBe instanceOf<HexMode>()
    }

    @Test
    fun `detects hex mode by content - high non-printable ratio`() = runTest {
        // Given: Unknown extension with mostly non-printable characters
        val binaryContent = buildString {
            // Add some text
            append("Header")
            // Add lots of non-printable bytes (>30%)
            repeat(100) {
                append(0x01.toChar())
                append(0x02.toChar())
                append(0x03.toChar())
            }
        }
        val dataSource = InMemoryDataSource(workspaceId, binaryContent)
        val testPath = mockPath("mystery.file")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect HexMode based on non-printable ratio
        mode shouldBe instanceOf<HexMode>()
    }

    @Test
    fun `handles empty file - defaults to text mode`() = runTest {
        // Given: Empty file
        val dataSource = InMemoryDataSource(workspaceId, "")
        val testPath = mockPath("empty.txt")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should default to TextMode
        mode shouldBe instanceOf<TextMode>()
    }

    @Test
    fun `handles null path - uses content analysis`() = runTest {
        // Given: Null path (in-memory) with text content
        val dataSource = InMemoryDataSource(workspaceId, "Some text content")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(null, dataSource)

        // Then: Should detect TextMode based on content
        mode shouldBe instanceOf<TextMode>()
    }

    @Test
    fun `detects json files as text`() = runTest {
        // Given: JSON file
        val jsonContent = """{"key": "value", "number": 123}"""
        val dataSource = InMemoryDataSource(workspaceId, jsonContent)
        val testPath = mockPath("config.json")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect TextMode
        mode shouldBe instanceOf<TextMode>()
    }

    @Test
    fun `detects markdown files as text`() = runTest {
        // Given: Markdown file
        val mdContent = "# Header\n\nSome **bold** text"
        val dataSource = InMemoryDataSource(workspaceId, mdContent)
        val testPath = mockPath("README.md")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect TextMode
        mode shouldBe instanceOf<TextMode>()
    }

    @Test
    fun `detects apk files as binary`() = runTest {
        // Given: APK file
        val dataSource = InMemoryDataSource(workspaceId, "")
        val testPath = mockPath("app.apk")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect HexMode
        mode shouldBe instanceOf<HexMode>()
    }

    @Test
    fun `content analysis takes precedence when extension unknown`() = runTest {
        // Given: Unknown extension with clearly text content
        val textContent = """
            Line 1 of text
            Line 2 of text
            Line 3 of text
        """.trimIndent()
        val dataSource = InMemoryDataSource(workspaceId, textContent)
        val testPath = mockPath("file.unknown")

        // When: Analyzing the file
        val mode = analyzer.analyzeFile(testPath, dataSource)

        // Then: Should detect TextMode based on content analysis
        mode shouldBe instanceOf<TextMode>()
    }

    /**
     * Helper to create test path
     */
    private fun mockPath(name: String): eu.darken.butler.common.files.APath<*> {
        return eu.darken.butler.common.files.LocalPath.build("/mock/$name")
    }
}

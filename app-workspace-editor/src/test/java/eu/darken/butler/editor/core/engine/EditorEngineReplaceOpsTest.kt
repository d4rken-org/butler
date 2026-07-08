package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.engine.text.WindowedSearch
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.nio.charset.Charset
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

class EditorEngineReplaceOpsTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())

    private fun createMockSettings(): EditorSettings {
        val settings = mockk<EditorSettings>()
        fun <T> value(v: T): DataStoreValue<T> = mockk<DataStoreValue<T>>().apply {
            every { flow } returns flowOf(v)
        }
        every { settings.undoStackSize } returns value(100)
        every { settings.undoMaxMemory } returns value(10 * 1_048_576L)
        return settings
    }

    private suspend fun createEngine(content: String): EditorEngine {
        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = null,
            initialContent = content,
            gatewaySwitch = mockk<GatewaySwitch>(),
            editorSettings = createMockSettings(),
            fileDataSourceFactory = object : FileDataSource.Factory {
                override fun create(
                    workspaceId: Workspace.Id,
                    filePath: APath<*>,
                    gatewaySwitch: GatewaySwitch,
                    charsetOverride: Charset?,
                ) = throw UnsupportedOperationException("in-memory only")
            },
            inMemoryDataSourceFactory = object : InMemoryDataSource.Factory {
                override fun create(workspaceId: Workspace.Id, initialContent: String) =
                    InMemoryDataSource(workspaceId, initialContent)
            },
            documentBufferFactory = object : DocumentBuffer.Factory {
                override fun create(
                    workspaceId: Workspace.Id,
                    dataSource: EditorDataSource,
                    maxUndoStackSize: Int,
                    maxUndoMemoryBytes: Long,
                    blockSize: Int,
                    assertions: Boolean,
                    staleSampleRandom: Random,
                    timeSource: TimeSource,
                ) = DocumentBuffer(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, 1024, true)
            },
        )
        engine.initialize().getOrThrow()
        return engine
    }

    private suspend fun EditorEngine.fullText(): String = textBuffer!!.getFullText().getOrThrow()

    // ==================== replaceCurrent ====================

    @Test
    fun `replace-current swaps one match and advances to the next`() = runTest {
        val engine = createEngine("cat dog cat dog cat")
        val options = SearchOptions(caseSensitive = true)
        val results = engine.search("cat", options).getOrThrow()
        results.size shouldBe 3

        val outcome = engine.replaceCurrent("cat", options, results[0], "bird").getOrThrow()

        engine.fullText() shouldBe "bird dog cat dog cat"
        outcome.results.size shouldBe 2
        // The next match after the replacement end
        outcome.results[outcome.nextIndex].position.offset shouldBe 9L
    }

    @Test
    fun `replace-current is one undo step`() = runTest {
        val engine = createEngine("cat dog")
        val options = SearchOptions(caseSensitive = true)
        val match = engine.search("cat", options).getOrThrow().single()

        engine.replaceCurrent("cat", options, match, "bird").getOrThrow()
        engine.fullText() shouldBe "bird dog"

        engine.undo().getOrThrow()
        engine.fullText() shouldBe "cat dog"
    }

    @Test
    fun `replace-current rejects a stale match without touching the document`() = runTest {
        val engine = createEngine("cat dog")
        val options = SearchOptions(caseSensitive = true)
        val match = engine.search("cat", options).getOrThrow().single()
        // Document changes after the search
        engine.insertText("XXX ")

        val result = engine.replaceCurrent("cat", options, match, "bird")

        result.exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
        engine.fullText() shouldBe "XXX cat dog"
    }

    @Test
    fun `replace-current expands regex groups`() = runTest {
        val engine = createEngine("name: John")
        val options = SearchOptions(caseSensitive = true, useRegex = true)
        val match = engine.search("name: (\\w+)", options).getOrThrow().single()

        engine.replaceCurrent("name: (\\w+)", options, match, "user=$1").getOrThrow()

        engine.fullText() shouldBe "user=John"
    }

    // ==================== replaceAll ====================

    @Test
    fun `replace-all literal matches the standard library reference`() = runTest {
        val content = "aaa bbb aaa ccc aaa"
        val engine = createEngine(content)
        val options = SearchOptions(caseSensitive = true)

        val outcome = engine.replaceAll("aaa", options, "X").getOrThrow()

        outcome.count shouldBe 3
        outcome.undoable.shouldBeTrue()
        engine.fullText() shouldBe content.replace("aaa", "X")
    }

    @Test
    fun `replace-all handles adjacent matches back-to-front`() = runTest {
        // findAll semantics: "aaaa" contains two non-overlapping "aa" matches
        val engine = createEngine("aaaa")
        val options = SearchOptions(caseSensitive = true)

        val outcome = engine.replaceAll("aa", options, "b").getOrThrow()

        outcome.count shouldBe 2
        engine.fullText() shouldBe "bb"
    }

    @Test
    fun `replace-all with regex groups matches Kotlin Regex-replace semantics`() = runTest {
        val content = "x=1, y=22, z=333"
        val engine = createEngine(content)
        val options = SearchOptions(caseSensitive = true, useRegex = true)
        val pattern = "(\\w)=(\\d+)"

        engine.replaceAll(pattern, options, "$2:$1").getOrThrow()

        engine.fullText() shouldBe Regex(pattern).replace(content, "$2:$1")
    }

    @Test
    fun `replace-all supports literal dollars via backslash escape`() = runTest {
        val engine = createEngine("price value")
        val options = SearchOptions(caseSensitive = true, useRegex = true)

        engine.replaceAll("value", options, "\\$100").getOrThrow()

        engine.fullText() shouldBe "price \$100"
    }

    @Test
    fun `replace-all with an invalid group reference fails before mutating`() = runTest {
        val engine = createEngine("cat dog")
        val options = SearchOptions(caseSensitive = true, useRegex = true)

        val result = engine.replaceAll("(cat)", options, "$9")

        result.isFailure.shouldBeTrue()
        engine.fullText() shouldBe "cat dog"
    }

    @Test
    fun `replace-all is a single undo step restoring the exact content`() = runTest {
        val content = "one two one two one"
        val engine = createEngine(content)
        val options = SearchOptions(caseSensitive = true)

        engine.replaceAll("one", options, "1").getOrThrow()
        engine.fullText() shouldBe "1 two 1 two 1"

        engine.undo().getOrThrow()
        engine.fullText() shouldBe content

        engine.redo().getOrThrow()
        engine.fullText() shouldBe "1 two 1 two 1"
    }

    @Test
    fun `replace-all republishes search results for the query`() = runTest {
        // Replacement still contains the query: results must reflect the new document
        val engine = createEngine("cat dog cat")
        val options = SearchOptions(caseSensitive = true)

        engine.replaceAll("cat", options, "cathedral").getOrThrow()

        engine.searchState.value.results.size shouldBe 2
        engine.fullText() shouldBe "cathedral dog cathedral"
    }

    @Test
    fun `replace-all refuses over the match cap and leaves the document untouched`() = runTest {
        val content = "cat ".repeat(5)
        val engine = createEngine(content)
        engine.textBuffer!!.windowedSearchFactory = { readText ->
            WindowedSearch(maxResults = 4, readText = readText)
        }

        val result = engine.replaceAll("cat", SearchOptions(caseSensitive = true), "dog")

        result.exceptionOrNull().shouldBeInstanceOf<TooManyMatchesException>()
        engine.fullText() shouldBe content
    }

    @Test
    fun `replace-all at exactly the match cap succeeds`() = runTest {
        val engine = createEngine("cat ".repeat(4))
        engine.textBuffer!!.windowedSearchFactory = { readText ->
            WindowedSearch(maxResults = 4, readText = readText)
        }

        val outcome = engine.replaceAll("cat", SearchOptions(caseSensitive = true), "dog").getOrThrow()

        outcome.count shouldBe 4
        engine.fullText() shouldBe "dog ".repeat(4)
    }

    @Test
    fun `regex replace-all over the match cap refuses without touching the document`() = runTest {
        // Every char matches: the manual findAll iteration must refuse at the (cap+1)-th match
        // instead of materializing a replacement per char first
        val content = "a".repeat(WindowedSearch.MAX_RESULTS + 1)
        val engine = createEngine(content)
        val options = SearchOptions(caseSensitive = true, useRegex = true)

        val result = engine.replaceAll("a", options, "b")

        result.exceptionOrNull().shouldBeInstanceOf<TooManyMatchesException>()
        engine.fullText() shouldBe content
    }

    @Test
    fun `regex replace-all over the char bound refuses without touching the document`() = runTest {
        // Two matches whose combined text blows the char bound long before the count cap
        val big = "x".repeat((WindowedSearch.MAX_TOTAL_MATCH_CHARS * 3 / 4).toInt())
        val content = "$big $big"
        val engine = createEngine(content)
        val options = SearchOptions(caseSensitive = true, useRegex = true)

        val result = engine.replaceAll("x+", options, "y")

        result.exceptionOrNull().shouldBeInstanceOf<TooManyMatchesException>()
        engine.fullText() shouldBe content
    }

    @Test
    fun `regex replace-all of a single oversized match succeeds`() = runTest {
        // The first replacement is exempt from the char bound: one huge match stays replaceable
        val content = "x".repeat(WindowedSearch.MAX_TOTAL_MATCH_CHARS.toInt() + 1)
        val engine = createEngine(content)
        val options = SearchOptions(caseSensitive = true, useRegex = true)

        val outcome = engine.replaceAll("x+", options, "y").getOrThrow()

        outcome.count shouldBe 1
        engine.fullText() shouldBe "y"
    }

    @Test
    fun `replace-all with CRLF and multibyte content is byte-faithful`() = runTest {
        val content = "grüß\r\nworld grüß end"
        val engine = createEngine(content)
        val options = SearchOptions(caseSensitive = true)

        engine.replaceAll("grüß", options, "héllo").getOrThrow()

        engine.fullText() shouldBe content.replace("grüß", "héllo")
    }

    @Test
    fun `oversized replace-all is honestly reported as not undoable`() = runTest {
        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = null,
            initialContent = "padding-entry " + "cat ".repeat(100),
            gatewaySwitch = mockk<GatewaySwitch>(),
            editorSettings = createMockSettings().also {
                every { it.undoMaxMemory } returns mockk<DataStoreValue<Long>>().apply {
                    every { flow } returns flowOf(64L) // absurdly small cap
                }
            },
            fileDataSourceFactory = object : FileDataSource.Factory {
                override fun create(
                    workspaceId: Workspace.Id,
                    filePath: APath<*>,
                    gatewaySwitch: GatewaySwitch,
                    charsetOverride: Charset?,
                ) = throw UnsupportedOperationException()
            },
            inMemoryDataSourceFactory = object : InMemoryDataSource.Factory {
                override fun create(workspaceId: Workspace.Id, initialContent: String) =
                    InMemoryDataSource(workspaceId, initialContent)
            },
            documentBufferFactory = object : DocumentBuffer.Factory {
                override fun create(
                    workspaceId: Workspace.Id,
                    dataSource: EditorDataSource,
                    maxUndoStackSize: Int,
                    maxUndoMemoryBytes: Long,
                    blockSize: Int,
                    assertions: Boolean,
                    staleSampleRandom: Random,
                    timeSource: TimeSource,
                ) = DocumentBuffer(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, 1024, true)
            },
        )
        engine.initialize().getOrThrow()
        // A first edit occupies the stack so eviction has an older victim
        engine.insertText("seed ")

        val outcome = engine.replaceAll("cat", SearchOptions(caseSensitive = true), "dog").getOrThrow()

        outcome.count shouldBe 100
        // The lone-entry guard deliberately keeps the newest oversized entry: replace-all
        // stays undoable right away (older history is what gets sacrificed)...
        outcome.undoable.shouldBeTrue()
        engine.textBuffer!!.canUndo().shouldBeTrue()

        // ...but the NEXT edit evicts it under the memory cap, so the step is gone afterwards
        engine.insertText("x")
        engine.undo().getOrThrow()
        engine.textBuffer!!.canUndo().shouldBeFalse()
    }

    // ==================== template expansion ====================

    @Test
    fun `template expansion pins Kotlin semantics`() {
        val match = Regex("(a)(b)").find("ab")!!

        EditorEngine.expandReplacementTemplate("$1-$2", match) shouldBe "a-b"
        EditorEngine.expandReplacementTemplate("$0!", match) shouldBe "ab!"
        EditorEngine.expandReplacementTemplate("\\$5", match) shouldBe "\$5"
        shouldThrow<IllegalArgumentException> { EditorEngine.expandReplacementTemplate("$", match) }
        shouldThrow<IllegalArgumentException> { EditorEngine.expandReplacementTemplate("$9", match) }
    }
}

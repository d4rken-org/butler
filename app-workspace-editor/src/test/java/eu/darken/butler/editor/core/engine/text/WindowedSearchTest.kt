package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.editor.core.engine.SearchOptions
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.random.Random

class WindowedSearchTest : BaseTest() {

    private suspend fun searchAll(
        content: String,
        query: String,
        options: SearchOptions = SearchOptions(caseSensitive = true),
        windowSize: Int = 32,
        minOverlap: Int = 8,
        regexFullScanCap: Int = WindowedSearch.REGEX_FULL_SCAN_CAP,
    ): List<WindowedSearch.Match> {
        val search = WindowedSearch(windowSize, minOverlap, regexFullScanCap) { start, end ->
            content.substring(start.toInt(), end.toInt())
        }
        return search.search(content.length.toLong(), query, options)
    }

    private fun refPosition(content: String, offset: Int): Pair<Int, Int> {
        var line = 0
        var lineStart = 0
        var i = 0
        while (i < offset) {
            when (content[i]) {
                '\n' -> {
                    line++
                    lineStart = i + 1
                    i++
                }
                '\r' -> if (i + 1 < content.length && content[i + 1] == '\n') {
                    if (i + 2 <= offset) {
                        line++
                        lineStart = i + 2
                        i += 2
                    } else {
                        i = offset
                    }
                } else {
                    line++
                    lineStart = i + 1
                    i++
                }
                else -> i++
            }
        }
        return line to (offset - lineStart)
    }

    @Test
    fun `literal match spanning window edges found exactly once`() = runTest {
        val content = "x".repeat(20) + "NEEDLE" + "y".repeat(30) + "NEEDLE" + "z".repeat(10)
        val results = searchAll(content, "NEEDLE")
        results.map { it.offset } shouldBe listOf(20L, 56L)
        results.all { it.matchText == "NEEDLE" }.shouldBeTrue()
    }

    @Test
    fun `single char needle at every position matches reference`() = runTest {
        val content = buildString { repeat(120) { append(if (it % 7 == 0) 'N' else 'x') } }
        val results = searchAll(content, "N")
        val expected = content.indices.filter { content[it] == 'N' }.map { it.toLong() }
        results.map { it.offset } shouldBe expected
    }

    @Test
    fun `long literal grows the window`() = runTest {
        val needle = "L".repeat(50)
        val content = "a".repeat(40) + needle + "b".repeat(40)
        searchAll(content, needle).map { it.offset } shouldBe listOf(40L)
    }

    @Test
    fun `zero width regex matches are skipped without hanging`() = runTest {
        val content = "bb" + "a".repeat(5) + "bb"
        val results = searchAll(content, "a*", SearchOptions(useRegex = true))
        results.map { it.offset } shouldBe listOf(2L)
        results[0].matchText shouldBe "aaaaa"
        searchAll(content, "(?=b)", SearchOptions(useRegex = true)).shouldBeEmpty()
    }

    @Test
    fun `whole word matching`() = runTest {
        val content = "cat concatenate cat scatter cat."
        val results = searchAll(content, "cat", SearchOptions(caseSensitive = true, wholeWord = true))
        results.map { it.offset } shouldBe listOf(0L, 16L, 28L)
    }

    @Test
    fun `literal query is regex escaped`() = runTest {
        val content = "a.b axb a.b"
        searchAll(content, "a.b").map { it.offset } shouldBe listOf(0L, 8L)
    }

    @Test
    fun `regex query is used as-is`() = runTest {
        val content = "a.b axb ayb"
        searchAll(content, "a.b", SearchOptions(useRegex = true)).map { it.offset } shouldBe
            listOf(0L, 4L, 8L)
    }

    @Test
    fun `case insensitive search`() = runTest {
        val content = "Hello hELLo HELLO xhello"
        searchAll(content, "hello", SearchOptions(caseSensitive = false)).size shouldBe 4
    }

    @Test
    fun `invalid regex returns empty`() = runTest {
        searchAll("content", "([", SearchOptions(useRegex = true)).shouldBeEmpty()
    }

    @Test
    fun `empty query and empty content`() = runTest {
        searchAll("abc", "").shouldBeEmpty()
        searchAll("", "a").shouldBeEmpty()
    }

    @Test
    fun `line and column across windows and CRLF endings`() = runTest {
        val lines = listOf("alpha NEEDLE", "beta", "NEEDLE gamma", "delta NEEDLE end", "NEEDLE")
        val content = lines.joinToString("\r\n")
        val results = searchAll(content, "NEEDLE")
        val expected = Regex("NEEDLE").findAll(content).map { it.range.first }.toList()
        results.map { it.offset } shouldBe expected.map { it.toLong() }
        for (result in results) {
            val (line, column) = refPosition(content, result.offset.toInt())
            result.line shouldBe line
            result.column shouldBe column
        }
    }

    @Test
    fun `multibyte offsets are char based`() = runTest {
        val content = "中文中文\n😀😀\r\nNEEDLE 中 NEEDLE"
        val results = searchAll(content, "NEEDLE")
        results.size shouldBe 2
        results[0].offset shouldBe 11L
        results[0].line shouldBe 2
        results[0].column shouldBe 0
        results[1].offset shouldBe 20L
        results[1].line shouldBe 2
        results[1].column shouldBe 9
    }

    @Test
    fun `dense matches across many windows equal whole-string reference`() = runTest {
        val random = Random(7)
        val content = buildString {
            repeat(400) {
                when (random.nextInt(6)) {
                    0 -> append("NEED")
                    1 -> append("NEEDLE")
                    2 -> append('\n')
                    3 -> append("\r\n")
                    else -> append('x')
                }
            }
        }
        val results = searchAll(content, "NEEDLE")
        val expected = Regex(Regex.escape("NEEDLE")).findAll(content).map { it.range.first.toLong() }.toList()
        results.map { it.offset } shouldBe expected
        for (result in results) {
            val (line, column) = refPosition(content, result.offset.toInt())
            result.line shouldBe line
            result.column shouldBe column
        }
    }

    @Test
    fun `results are sorted by offset`() = runTest {
        val content = "apple banana apple cherry apple date"
        val results = searchAll(content, "apple")
        results.map { it.offset } shouldBe listOf(0L, 13L, 26L)
    }

    @Test
    fun `whole word does not see a false boundary at window start`() = runTest {
        // Window 2 starts right at "cat", but the real preceding char is a word char
        val content = "xxxxxxcat cat"
        val results = searchAll(
            content,
            "cat",
            SearchOptions(caseSensitive = true, wholeWord = true),
            windowSize = 8,
            minOverlap = 2,
        )
        results.map { it.offset } shouldBe listOf(10L)
    }

    @Test
    fun `pad matches do not consume real matches at window handoff`() = runTest {
        // A match starting in the left pad must not eat the non-overlap slot of a core match
        val content = "xxxxaaaazz"
        val results = searchAll(content, "aa", windowSize = 8, minOverlap = 2)
        val expected = Regex(Regex.escape("aa")).findAll(content).map { it.range.first.toLong() }.toList()
        results.map { it.offset } shouldBe expected
    }

    @Test
    fun `whole word does not see a false boundary at window end`() = runTest {
        // Window 1 text ends right after "cat", but the real next char is a word char
        val content = "ab cats"
        val results = searchAll(
            content,
            "cat",
            SearchOptions(caseSensitive = true, wholeWord = true),
            windowSize = 6,
            minOverlap = 2,
        )
        results.shouldBeEmpty()
    }

    @Test
    fun `self-overlapping needle straddling stride boundaries equals whole-string findAll`() = runTest {
        // Consumption state must carry across windows: after "aa" matches at 0 in "aaa",
        // the next window must not re-match at 1
        val contents = listOf(
            "aaa".repeat(20) + "b" + "a".repeat(50),
            "a".repeat(100),
            "abab".repeat(30) + "ab",
        )
        for (content in contents) {
            for (query in listOf("aa", "abab")) {
                val expected = Regex(Regex.escape(query)).findAll(content)
                    .map { it.range.first.toLong() }.toList()
                for (windowSize in listOf(8, 16, 32, 64)) {
                    for (overlap in listOf(2, 4, 8)) {
                        if (windowSize <= overlap) continue
                        val results = searchAll(content, query, windowSize = windowSize, minOverlap = overlap)
                        results.map { it.offset } shouldBe expected
                    }
                }
            }
        }
    }

    @Test
    fun `windowed literal search equals whole-string findAll on random content`() = runTest {
        val random = Random(42)
        val alphabet = listOf("a", "b", "ab", "aa", "\n", "\r\n", "中", "😀", "NEED", "NEEDLE")
        val queries = listOf("aa", "abab", "a", "中", "NEEDLE", "aba")
        repeat(40) {
            val content = buildString { repeat(random.nextInt(50, 300)) { append(alphabet.random(random)) } }
            val query = queries.random(random)
            val expected = Regex(Regex.escape(query)).findAll(content)
                .map { it.range.first.toLong() to it.value }.toList()
            for (windowSize in listOf(8, 16, 32, 64)) {
                for (overlap in listOf(2, 4, 8)) {
                    if (windowSize <= overlap) continue
                    val results = searchAll(content, query, windowSize = windowSize, minOverlap = overlap)
                    results.map { it.offset to it.matchText } shouldBe expected
                    for (result in results) {
                        val (line, column) = refPosition(content, result.offset.toInt())
                        result.line shouldBe line
                        result.column shouldBe column
                    }
                }
            }
        }
    }

    @Test
    fun `windowed whole-word search equals whole-string findAll on random content`() = runTest {
        val random = Random(43)
        val parts = listOf("cat", "cats", "concat", "cat.", " ", "\n", "x", "-")
        repeat(30) {
            val content = buildString { repeat(random.nextInt(30, 150)) { append(parts.random(random)) } }
            val expected = Regex("\\bcat\\b").findAll(content).map { it.range.first.toLong() }.toList()
            for (windowSize in listOf(8, 16, 32)) {
                for (overlap in listOf(2, 4)) {
                    val results = searchAll(
                        content,
                        "cat",
                        SearchOptions(caseSensitive = true, wholeWord = true),
                        windowSize = windowSize,
                        minOverlap = overlap,
                    )
                    results.map { it.offset } shouldBe expected
                }
            }
        }
    }

    @Test
    fun `regex anchors are document anchors under full scan`() = runTest {
        val content = "foo start\nfoo middle\nend foo"
        // Tiny windows would previously anchor-match at every window start
        searchAll(content, "^foo", SearchOptions(useRegex = true), windowSize = 8, minOverlap = 2)
            .map { it.offset } shouldBe listOf(0L)
        searchAll(content, "foo$", SearchOptions(useRegex = true), windowSize = 8, minOverlap = 2)
            .map { it.offset } shouldBe listOf(25L)
    }

    @Test
    fun `regex multiline flag matches line starts under full scan`() = runTest {
        val content = "foo a\nbar b\nfoo c"
        val results = searchAll(content, "(?m)^foo", SearchOptions(useRegex = true), windowSize = 8, minOverlap = 2)
        results.map { it.offset } shouldBe listOf(0L, 12L)
        results.map { it.line } shouldBe listOf(0, 2)
        results.map { it.column } shouldBe listOf(0, 0)
    }

    @Test
    fun `regex lookahead across former window boundaries`() = runTest {
        val content = "x".repeat(30) + "ab" + "x".repeat(30)
        val results = searchAll(content, "a(?=b)", SearchOptions(useRegex = true), windowSize = 8, minOverlap = 2)
        results.map { it.offset } shouldBe listOf(30L)
        results[0].matchText shouldBe "a"
    }

    @Test
    fun `regex match longer than overlap found exactly under full scan`() = runTest {
        val content = "a".repeat(10) + "L".repeat(40) + "a".repeat(10)
        val results = searchAll(content, "L+", SearchOptions(useRegex = true), windowSize = 8, minOverlap = 2)
        results.map { it.offset } shouldBe listOf(10L)
        results[0].matchText shouldBe "L".repeat(40)
    }

    @Test
    fun `regex above full-scan cap falls back to windowed scan`() = runTest {
        val content = "foo " + "x".repeat(60) + " foo"
        // Plain patterns still work windowed above the cap
        searchAll(content, "foo", SearchOptions(useRegex = true), windowSize = 16, minOverlap = 4, regexFullScanCap = 8)
            .map { it.offset } shouldBe listOf(0L, 65L)
        // Documented limitation: anchors are unreliable above the cap (window starts anchor)
        val anchored = searchAll(content, "^foo", SearchOptions(useRegex = true), windowSize = 16, minOverlap = 4, regexFullScanCap = 8)
        anchored.map { it.offset }.contains(0L).shouldBeTrue()
    }
}

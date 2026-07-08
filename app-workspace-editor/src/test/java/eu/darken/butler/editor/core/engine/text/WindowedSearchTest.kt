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

    private suspend fun searchOutcome(
        content: String,
        query: String,
        options: SearchOptions = SearchOptions(caseSensitive = true),
        windowSize: Int = 32,
        minOverlap: Int = 8,
        regexFullScanCap: Int = WindowedSearch.REGEX_FULL_SCAN_CAP,
        maxResults: Int = WindowedSearch.MAX_RESULTS,
        maxTotalMatchChars: Long = WindowedSearch.MAX_TOTAL_MATCH_CHARS,
        onRead: () -> Unit = {},
    ): WindowedSearch.Outcome {
        val search = WindowedSearch(
            windowSize,
            minOverlap,
            regexFullScanCap,
            maxResults,
            maxTotalMatchChars,
        ) { start, end ->
            onRead()
            content.substring(start.toInt(), end.toInt())
        }
        return search.search(content.length.toLong(), query, options)
    }

    private suspend fun searchAll(
        content: String,
        query: String,
        options: SearchOptions = SearchOptions(caseSensitive = true),
        windowSize: Int = 32,
        minOverlap: Int = 8,
        regexFullScanCap: Int = WindowedSearch.REGEX_FULL_SCAN_CAP,
    ): List<WindowedSearch.Match> =
        searchOutcome(content, query, options, windowSize, minOverlap, regexFullScanCap).matches

    private fun refPosition(content: String, offset: Int): Pair<Long, Int> {
        var line = 0L
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
        results[0].line shouldBe 2L
        results[0].column shouldBe 0
        results[1].offset shouldBe 20L
        results[1].line shouldBe 2L
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
        results.map { it.line } shouldBe listOf(0L, 2L)
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
    fun `exactly at the result cap is not truncated`() = runTest {
        val content = "hit ".repeat(5)
        val outcome = searchOutcome(content, "hit", maxResults = 5)
        outcome.truncated shouldBe false
        outcome.matches.map { it.offset } shouldBe listOf(0L, 4L, 8L, 12L, 16L)
    }

    @Test
    fun `one match past the cap truncates to exactly the cap results`() = runTest {
        val content = "hit ".repeat(6)
        val capped = searchOutcome(content, "hit", maxResults = 5)
        capped.truncated shouldBe true
        capped.matches.size shouldBe 5

        // The retained matches are identical to an uncapped scan's first five
        val uncapped = searchOutcome(content, "hit")
        uncapped.truncated shouldBe false
        capped.matches shouldBe uncapped.matches.take(5)
    }

    @Test
    fun `huge matches trip the char bound before the count cap`() = runTest {
        val block = "L".repeat(40)
        val content = "$block $block $block"
        val outcome = searchOutcome(content, block, maxResults = 100, maxTotalMatchChars = 100L)
        outcome.truncated shouldBe true
        // 40 + 40 = 80 chars fit the bound; the third match would push it to 120
        outcome.matches.size shouldBe 2
    }

    @Test
    fun `a single oversized match is exempt from the char bound`() = runTest {
        // One match over the bound alone: retained, not truncated - a truncated outcome must
        // always carry at least one result
        val content = "L".repeat(200)
        val lone = searchOutcome(content, "L+", SearchOptions(useRegex = true), maxTotalMatchChars = 100L)
        lone.truncated shouldBe false
        lone.matches.size shouldBe 1
        lone.matches[0].matchText shouldBe content

        // With a second match following, the oversized first is retained and truncation
        // happens there instead
        val two = searchOutcome(
            "${"L".repeat(200)} ${"L".repeat(50)}",
            "L+",
            SearchOptions(useRegex = true),
            maxTotalMatchChars = 100L,
        )
        two.truncated shouldBe true
        two.matches.size shouldBe 1
        two.matches[0].offset shouldBe 0L
    }

    @Test
    fun `truncation stops decoding further windows`() = runTest {
        val content = "hit ".repeat(3) + "x".repeat(300)
        var reads = 0
        val outcome = searchOutcome(
            content,
            "hit",
            windowSize = 32,
            minOverlap = 8,
            maxResults = 2,
            onRead = { reads++ },
        )
        outcome.truncated shouldBe true
        outcome.matches.map { it.offset } shouldBe listOf(0L, 4L)
        // The third match in the first window trips the cap; no further window is read
        reads shouldBe 1
    }

    @Test
    fun `a truncating match first seen in the overlap is confirmed in the next window`() = runTest {
        // Window size 32, overlap 8 -> stride 24: the third match sits at offset 24, exactly at
        // window 1's accept limit. Overlap matches can be false (see the whole-word test below),
        // so the caps deliberately count only accepted matches - the scan decodes exactly one
        // more window and truncates there.
        val content = "hit hit " + "x".repeat(16) + "hit" + "x".repeat(200)
        var reads = 0
        val outcome = searchOutcome(
            content,
            "hit",
            windowSize = 32,
            minOverlap = 8,
            maxResults = 2,
            onRead = { reads++ },
        )
        outcome.truncated shouldBe true
        outcome.matches.map { it.offset } shouldBe listOf(0L, 4L)
        reads shouldBe 2
    }

    @Test
    fun `a false whole-word match in the overlap never causes truncation`() = runTest {
        // "abc" at offset 4 looks word-bounded at window 1's text edge, but the real document
        // continues with 'd' - counting it as truncation evidence would falsely refuse a
        // replace-all on a document with a single genuine match
        val outcome = searchOutcome(
            "abc abcd",
            "abc",
            SearchOptions(caseSensitive = true, wholeWord = true),
            windowSize = 6,
            minOverlap = 2,
            maxResults = 1,
        )
        outcome.truncated shouldBe false
        outcome.matches.map { it.offset } shouldBe listOf(0L)
    }

    @Test
    fun `regex above full-scan cap falls back to windowed scan`() = runTest {
        val content = "foo " + "x".repeat(60) + " foo"
        // Plain patterns still work windowed above the cap
        searchAll(content, "foo", SearchOptions(useRegex = true), windowSize = 16, minOverlap = 4, regexFullScanCap = 8)
            .map { it.offset } shouldBe listOf(0L, 65L)
        // Documented limitation, pinned EXACTLY with a contrasting case: window size 16 and
        // overlap 4 give a stride of 12, so a "foo" placed at offset 12 sits exactly at the
        // second window's start. Above the cap `^` anchors at every window start and falsely
        // matches it; the full scan does not. If either output changes, the fallback contract
        // changed.
        val anchorBait = "foo " + "x".repeat(8) + "foo" + "x".repeat(30)
        val fallback = searchAll(anchorBait, "^foo", SearchOptions(useRegex = true), windowSize = 16, minOverlap = 4, regexFullScanCap = 8)
        fallback.map { it.offset } shouldBe listOf(0L, 12L)

        // The same pattern under the full scan (cap not exceeded) only matches the true start
        val exact = searchAll(anchorBait, "^foo", SearchOptions(useRegex = true), windowSize = 16, minOverlap = 4)
        exact.map { it.offset } shouldBe listOf(0L)
    }
}

package eu.darken.butler.searcher.core.engine

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PatternMatcherTest : BaseTest() {

    // === matches() - Substring tests ===

    @Test
    fun `matches - substring case insensitive finds match`() {
        val result = PatternMatcher.matches(
            text = "Hello World",
            pattern = "world",
            options = PatternOptions(caseSensitive = false),
        )
        result.isFound shouldBe true
    }

    @Test
    fun `matches - substring case insensitive no match`() {
        val result = PatternMatcher.matches(
            text = "Hello World",
            pattern = "foo",
            options = PatternOptions(caseSensitive = false),
        )
        result.isFound shouldBe false
    }

    @Test
    fun `matches - substring case sensitive finds exact match`() {
        val result = PatternMatcher.matches(
            text = "Hello World",
            pattern = "World",
            options = PatternOptions(caseSensitive = true),
        )
        result.isFound shouldBe true
    }

    @Test
    fun `matches - substring case sensitive rejects wrong case`() {
        val result = PatternMatcher.matches(
            text = "Hello World",
            pattern = "world",
            options = PatternOptions(caseSensitive = true),
        )
        result.isFound shouldBe false
    }

    // === matches() - Whole word tests ===

    @Test
    fun `matches - whole word matches complete word`() {
        val result = PatternMatcher.matches(
            text = "Hello World",
            pattern = "World",
            options = PatternOptions(wholeWord = true),
        )
        result.isFound shouldBe true
    }

    @Test
    fun `matches - whole word rejects partial word`() {
        val result = PatternMatcher.matches(
            text = "HelloWorld",
            pattern = "World",
            options = PatternOptions(wholeWord = true),
        )
        result.isFound shouldBe false
    }

    @Test
    fun `matches - whole word case insensitive`() {
        val result = PatternMatcher.matches(
            text = "Hello World",
            pattern = "world",
            options = PatternOptions(wholeWord = true, caseSensitive = false),
        )
        result.isFound shouldBe true
    }

    @Test
    fun `matches - whole word with special regex chars is escaped and does not throw`() {
        // Special regex chars like [, ], (, ) should be escaped and not cause regex errors
        // Note: Word boundaries (\b) only work around word chars, so [test] won't match
        // with wholeWord=true because brackets are non-word characters
        val result = PatternMatcher.matches(
            text = "Value is [test]",
            pattern = "[test]",
            options = PatternOptions(wholeWord = true),
        )
        // Pattern is escaped correctly (no exception), but no match due to word boundary rules
        result.shouldBeInstanceOf<MatchResult.NotFound>()
    }

    @Test
    fun `matches - special regex chars in plain substring mode`() {
        // Without wholeWord, special chars are treated literally
        val result = PatternMatcher.matches(
            text = "Value is [test]",
            pattern = "[test]",
            options = PatternOptions(wholeWord = false, useRegex = false),
        )
        result.isFound shouldBe true
    }

    // === matches() - Regex tests ===

    @Test
    fun `matches - regex finds pattern`() {
        val result = PatternMatcher.matches(
            text = "file123.txt",
            pattern = "file\\d+\\.txt",
            options = PatternOptions(useRegex = true),
        )
        result.isFound shouldBe true
    }

    @Test
    fun `matches - regex case insensitive`() {
        val result = PatternMatcher.matches(
            text = "FILE123.TXT",
            pattern = "file\\d+\\.txt",
            options = PatternOptions(useRegex = true, caseSensitive = false),
        )
        result.isFound shouldBe true
    }

    @Test
    fun `matches - regex case sensitive rejects wrong case`() {
        val result = PatternMatcher.matches(
            text = "FILE123.TXT",
            pattern = "file\\d+\\.txt",
            options = PatternOptions(useRegex = true, caseSensitive = true),
        )
        result.isFound shouldBe false
    }

    @Test
    fun `matches - invalid regex returns InvalidPattern`() {
        val result = PatternMatcher.matches(
            text = "test",
            pattern = "[invalid",
            options = PatternOptions(useRegex = true),
        )
        result.shouldBeInstanceOf<MatchResult.InvalidPattern>()
        (result as MatchResult.InvalidPattern).reason.contains("Invalid regex") shouldBe true
    }

    @Test
    fun `matches - blank pattern returns InvalidPattern`() {
        val result = PatternMatcher.matches(
            text = "test",
            pattern = "   ",
            options = PatternOptions(),
        )
        result.shouldBeInstanceOf<MatchResult.InvalidPattern>()
    }

    // === find() - Position tests ===

    @Test
    fun `find - returns correct position for substring`() {
        val result = PatternMatcher.find(
            text = "Hello World",
            pattern = "World",
            options = PatternOptions(),
        )
        result.shouldBeInstanceOf<MatchResult.Found>()
        val found = result as MatchResult.Found
        found.start shouldBe 6
        found.end shouldBe 11
    }

    @Test
    fun `find - returns correct position for regex`() {
        val result = PatternMatcher.find(
            text = "User: john_doe",
            pattern = "\\w+_\\w+",
            options = PatternOptions(useRegex = true),
        )
        result.shouldBeInstanceOf<MatchResult.Found>()
        val found = result as MatchResult.Found
        found.start shouldBe 6
        found.end shouldBe 14
    }

    @Test
    fun `find - returns correct position for whole word`() {
        val result = PatternMatcher.find(
            text = "abc def ghi",
            pattern = "def",
            options = PatternOptions(wholeWord = true),
        )
        result.shouldBeInstanceOf<MatchResult.Found>()
        val found = result as MatchResult.Found
        found.start shouldBe 4
        found.end shouldBe 7
    }

    @Test
    fun `find - returns NotFound when no match`() {
        val result = PatternMatcher.find(
            text = "Hello World",
            pattern = "xyz",
            options = PatternOptions(),
        )
        result.shouldBeInstanceOf<MatchResult.NotFound>()
    }

    @Test
    fun `find - regex with groups returns full match position`() {
        val result = PatternMatcher.find(
            text = "name: John Smith",
            pattern = "(\\w+): (\\w+) (\\w+)",
            options = PatternOptions(useRegex = true),
        )
        result.shouldBeInstanceOf<MatchResult.Found>()
        val found = result as MatchResult.Found
        found.start shouldBe 0
        found.end shouldBe 16
    }

    // === Edge cases ===

    @Test
    fun `matches - unicode text and pattern`() {
        val result = PatternMatcher.matches(
            text = "日本語テスト文字列",
            pattern = "テスト",
            options = PatternOptions(),
        )
        result.isFound shouldBe true
    }

    @Test
    fun `find - unicode returns correct positions`() {
        val result = PatternMatcher.find(
            text = "日本語テスト文字列",
            pattern = "テスト",
            options = PatternOptions(),
        )
        result.shouldBeInstanceOf<MatchResult.Found>()
        val found = result as MatchResult.Found
        found.start shouldBe 3
        found.end shouldBe 6
    }

    @Test
    fun `matches - empty text returns NotFound`() {
        val result = PatternMatcher.matches(
            text = "",
            pattern = "test",
            options = PatternOptions(),
        )
        result.shouldBeInstanceOf<MatchResult.NotFound>()
    }

    // === validate() ===

    @Test
    fun `validate - plain substring is valid`() {
        PatternMatcher.validate("needle", PatternOptions()) shouldBe null
    }

    @Test
    fun `validate - valid regex is valid`() {
        PatternMatcher.validate("file\\d+\\.txt", PatternOptions(useRegex = true)) shouldBe null
    }

    @Test
    fun `validate - invalid regex returns a reason`() {
        val reason = PatternMatcher.validate("[invalid", PatternOptions(useRegex = true))
        reason.shouldNotBeNull()
    }

    @Test
    fun `validate - blank pattern returns a reason`() {
        PatternMatcher.validate("   ", PatternOptions()) shouldBe "Pattern is blank"
    }

    @Test
    fun `validate - whole word with special regex chars is valid due to escaping`() {
        PatternMatcher.validate("[test]", PatternOptions(wholeWord = true)) shouldBe null
    }

    // === MatchResult helper tests ===

    @Test
    fun `MatchResult isFound returns true for Found`() {
        val result = MatchResult.Found(0, 5)
        result.isFound shouldBe true
    }

    @Test
    fun `MatchResult isFound returns false for NotFound`() {
        val result = MatchResult.NotFound
        result.isFound shouldBe false
    }

    @Test
    fun `MatchResult isFound returns false for InvalidPattern`() {
        val result = MatchResult.InvalidPattern("test")
        result.isFound shouldBe false
    }

    @Test
    fun `MatchResult toRange returns pair for Found`() {
        val result = MatchResult.Found(5, 10)
        result.toRange() shouldBe (5 to 10)
    }

    @Test
    fun `MatchResult toRange returns null for NotFound`() {
        val result = MatchResult.NotFound
        result.toRange() shouldBe null
    }

    @Test
    fun `MatchResult toRange returns null for InvalidPattern`() {
        val result = MatchResult.InvalidPattern("test")
        result.toRange() shouldBe null
    }
}

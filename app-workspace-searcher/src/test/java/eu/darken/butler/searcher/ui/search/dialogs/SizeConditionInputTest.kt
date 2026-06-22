package eu.darken.butler.searcher.ui.search.dialogs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SizeConditionInputTest : BaseTest() {

    @Test
    fun `bare number is treated as megabytes`() {
        normalizeSizeInput("100") shouldBe "100 MB"
    }

    @Test
    fun `bare number with surrounding whitespace is trimmed`() {
        normalizeSizeInput("  42  ") shouldBe "42 MB"
    }

    @Test
    fun `bare decimal number is treated as megabytes`() {
        normalizeSizeInput("1.5") shouldBe "1.5 MB"
        normalizeSizeInput("1,5") shouldBe "1,5 MB"
    }

    @Test
    fun `number with two separators is not treated as bare and passes through`() {
        normalizeSizeInput("1.5.5") shouldBe "1.5.5"
    }

    @Test
    fun `value with unit passes through unchanged`() {
        normalizeSizeInput("1 MB") shouldBe "1 MB"
        normalizeSizeInput("10mb") shouldBe "10mb"
        normalizeSizeInput(" 1.5 GB ") shouldBe "1.5 GB"
    }

    @Test
    fun `blank input returns null`() {
        normalizeSizeInput("") shouldBe null
        normalizeSizeInput("   ") shouldBe null
    }
}

package eu.darken.butler.common.files

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class FileNamesTest : BaseTest() {

    @Test
    fun `non-ASCII letters survive`() {
        sanitizeForFileName("厨房", 48) shouldBe "厨房"
        sanitizeForFileName("Küche", 48) shouldBe "Küche"
    }

    @Test
    fun `reserved characters become underscores`() {
        sanitizeForFileName("a/b", 48) shouldBe "a_b"
        sanitizeForFileName("a\\b:c*d?e\"f<g>h|i", 48) shouldBe "a_b_c_d_e_f_g_h_i"
    }

    @Test
    fun `control characters become underscores`() {
        sanitizeForFileName("a\u0000b\u001Fc", 48) shouldBe "a_b_c"
    }

    @Test
    fun `underscore runs collapse`() {
        sanitizeForFileName("a___b", 48) shouldBe "a_b"
        sanitizeForFileName("a/\\:b", 48) shouldBe "a_b"
    }

    @Test
    fun `truncation counts code points, not chars`() {
        // Each emoji is a surrogate pair: a char-based cap would cut one in half.
        sanitizeForFileName("😀😀😀", 2) shouldBe "😀😀"
        sanitizeForFileName("abcdef", 3) shouldBe "abc"
        sanitizeForFileName("abcdef", 6) shouldBe "abcdef"
    }

    @Test
    fun `truncation does not leave a trailing underscore`() {
        sanitizeForFileName("ab_cd", 3) shouldBe "ab"
    }

    @Test
    fun `a leading dot is dropped so the file is not hidden`() {
        sanitizeForFileName(".hidden", 48) shouldBe "hidden"
        sanitizeForFileName("/.Hidden", 48) shouldBe "Hidden"
        sanitizeForFileName("_._x", 48) shouldBe "x"
    }

    @Test
    fun `surrounding whitespace is stripped`() {
        sanitizeForFileName(" a ", 48) shouldBe "a"
        sanitizeForFileName("a_ ", 48) shouldBe "a"
        sanitizeForFileName("  a", 48) shouldBe "a"
    }

    @Test
    fun `a name of nothing but separators sanitizes to nothing`() {
        sanitizeForFileName("/\\:*", 48) shouldBe ""
        sanitizeForFileName("...", 48) shouldBe ""
    }
}

package eu.darken.butler.common.files.text

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class TextBoundaryTest : BaseTest() {

    private val gb18030 = TextCharsets.GB18030

    private fun ByteArray.cut(count: Int) = copyOf(size - count)

    // ==================== not truncated ====================

    @Test
    fun `a buffer that was not truncated is returned as it is`() {
        listOf(
            "世界".toByteArray(Charsets.UTF_8).cut(1) to Charsets.UTF_8,
            "你好".toByteArray(gb18030).cut(1) to gb18030,
            "Hi".toByteArray(Charsets.UTF_16LE).cut(1) to Charsets.UTF_16LE,
        ).forEach { (bytes, charset) ->
            TextBoundary.trim(bytes, charset, truncated = false) shouldBeSameInstanceAs bytes
            TextBoundary.trimToLine(bytes, charset, truncated = false) shouldBeSameInstanceAs bytes
        }
    }

    // ==================== UTF-16 ====================

    @Test
    fun `utf-16 is cut to an even length`() {
        val bytes = "Hi".toByteArray(Charsets.UTF_16LE)

        TextBoundary.trim(bytes.cut(1), Charsets.UTF_16LE, truncated = true) shouldBe bytes.cut(2)
        TextBoundary.trim(bytes, Charsets.UTF_16LE, truncated = true) shouldBe bytes
    }

    @Test
    fun `utf-16 drops a high surrogate whose partner was cut off`() {
        val le = "a😀".toByteArray(Charsets.UTF_16LE)
        val be = "a😀".toByteArray(Charsets.UTF_16BE)

        TextBoundary.trim(le.cut(2), Charsets.UTF_16LE, truncated = true) shouldBe le.copyOf(2)
        TextBoundary.trim(be.cut(2), Charsets.UTF_16BE, truncated = true) shouldBe be.copyOf(2)
        TextBoundary.trim(le, Charsets.UTF_16LE, truncated = true) shouldBe le
    }

    // ==================== UTF-8 ====================

    @Test
    fun `utf-8 drops an incomplete trailing sequence`() {
        val bytes = "a世界".toByteArray(Charsets.UTF_8)

        TextBoundary.trim(bytes.cut(1), Charsets.UTF_8, truncated = true) shouldBe "a世".toByteArray()
        TextBoundary.trim(bytes.cut(2), Charsets.UTF_8, truncated = true) shouldBe "a世".toByteArray()
        TextBoundary.trim(bytes, Charsets.UTF_8, truncated = true) shouldBe bytes
    }

    /** Every high byte of a single-byte encoding looks like a UTF-8 continuation byte. */
    @Test
    fun `utf-8 leaves a run without a lead byte alone`() {
        val bytes = ByteArray(64) { 0xA3.toByte() }

        TextBoundary.trim(bytes, Charsets.UTF_8, truncated = true) shouldBe bytes
    }

    // ==================== GB18030 ====================

    @Test
    fun `gb18030 drops an incomplete trailing sequence`() {
        val two = "a你好".toByteArray(gb18030)
        val four = "a😀".toByteArray(gb18030)

        TextBoundary.trim(two.cut(1), gb18030, truncated = true) shouldBe "a你".toByteArray(gb18030)
        (1..3).forEach { TextBoundary.trim(four.cut(it), gb18030, truncated = true) shouldBe byteArrayOf(0x61) }
        TextBoundary.trim(two, gb18030, truncated = true) shouldBe two
    }

    @Test
    fun `single-byte charsets are never trimmed`() {
        val bytes = byteArrayOf(0x61, 0xC4.toByte())

        TextBoundary.trim(bytes, Charsets.ISO_8859_1, truncated = true) shouldBe bytes
        TextBoundary.trim(bytes, TextCharsets.WINDOWS_1252, truncated = true) shouldBe bytes
    }

    // ==================== line cut ====================

    @Test
    fun `the line cut ends at the last line break`() {
        TextBoundary.trimToLine("one\ntwo\nthr".toByteArray(), Charsets.UTF_8, truncated = true) shouldBe
            "one\ntwo".toByteArray()
        TextBoundary.trimToLine("你好\n世界".toByteArray(gb18030), gb18030, truncated = true) shouldBe
            "你好".toByteArray(gb18030)
    }

    @Test
    fun `without a line break the line cut falls back to the charset trim`() {
        val bytes = "a世界".toByteArray(Charsets.UTF_8)

        TextBoundary.trimToLine(bytes.cut(1), Charsets.UTF_8, truncated = true) shouldBe "a世".toByteArray()
    }

    /** `Ċ` is `0A 01` in UTF-16LE: a 0x0A byte that is not a line break. */
    @Test
    fun `utf-16 is not cut at a 0x0A byte`() {
        val bytes = "aĊb".toByteArray(Charsets.UTF_16LE)

        TextBoundary.trimToLine(bytes, Charsets.UTF_16LE, truncated = true) shouldBe bytes
    }
}

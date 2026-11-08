package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.text.TextCharsets
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.ByteArrayInputStream

class TextPreviewLoaderTest : BaseTest() {

    private val path = LocalPath.build("/storage/emulated/0/Documents/notes.txt")
    private val source = ViewerSource.Stored(path)
    private val gatewaySwitch = mockk<GatewaySwitch>()

    private fun loaderFor(content: ByteArray): TextPreviewLoader {
        coEvery { gatewaySwitch.useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(gatewaySwitch)
        }
        // A fresh stream per open: the reader documents that gateway streams do not rewind, and a
        // shared one would let the probe consume what the preview is supposed to read.
        coEvery { gatewaySwitch.openInputStream(any()) } answers { ByteArrayInputStream(content) }
        return TextPreviewLoader(
            contentReader = readerFor(gatewaySwitch),
            dispatcherProvider = TestDispatcherProvider(),
        )
    }

    private fun loaderFor(content: String) = loaderFor(content.toByteArray())

    // ==================== the byte cap ====================

    @Test
    fun `a small file is served whole and unmarked`() = runTest2 {
        val preview = loaderFor("one\ntwo\nthree").preview(source)!!

        preview.lines shouldBe listOf("one", "two", "three")
        preview.charset shouldBe Charsets.UTF_8
        preview.isTruncated shouldBe false
    }

    @Test
    fun `a file exactly at the cap is not reported as truncated`() = runTest2 {
        val line = "a".repeat(999) + "\n"
        val content = ByteArray(TextPreviewLoader.MAX_PREVIEW_BYTES) {
            line[it % line.length].code.toByte()
        }

        val preview = loaderFor(content).preview(source)!!

        preview.isTruncated shouldBe false
    }

    @Test
    fun `a file past the cap is cut back to a whole line`() = runTest2 {
        // Lines of a length the cap cannot land on, so the cut is always mid-line.
        val content = ("a".repeat(999) + "\n").repeat(2000).toByteArray()

        val preview = loaderFor(content).preview(source)!!

        preview.isTruncated shouldBe true
        preview.truncation shouldBe TextPreview.Truncation.Bytes(TextPreviewLoader.MAX_PREVIEW_BYTES.toLong())
        preview.lines.all { it.length == 999 } shouldBe true
    }

    /** A cut in the middle of a multi-byte sequence must not survive into the rendered text. */
    @Test
    fun `truncation never leaves a replacement character`() = runTest2 {
        val content = "世界\n".repeat(TextPreviewLoader.MAX_PREVIEW_BYTES / 7 + 100).toByteArray()

        val preview = loaderFor(content).preview(source)!!

        preview.isTruncated shouldBe true
        preview.lines.none { it.contains('�') } shouldBe true
        preview.charset shouldBe Charsets.UTF_8
    }

    // ==================== the render bounds ====================

    /** A megabyte of bare newlines would otherwise be a million list entries. */
    @Test
    fun `a file with more lines than the cap is cut to it`() = runTest2 {
        val content = "\n".repeat(TextPreviewLoader.MAX_LINES + 500).toByteArray()

        val preview = loaderFor(content).preview(source)!!

        preview.lines.size shouldBe TextPreviewLoader.MAX_LINES
        preview.truncation shouldBe TextPreview.Truncation.Lines(TextPreviewLoader.MAX_LINES)
    }

    @Test
    fun `a file with exactly the line cap is not reported as truncated`() = runTest2 {
        val content = "x\n".repeat(TextPreviewLoader.MAX_LINES - 1) + "x"

        val preview = loaderFor(content).preview(source)!!

        preview.lines.size shouldBe TextPreviewLoader.MAX_LINES
        preview.isTruncated shouldBe false
    }

    /** Minified JSON is one line; Compose cannot shape a million characters of it. */
    @Test
    fun `an over-wide line is clipped and reported`() = runTest2 {
        val content = "b".repeat(TextPreviewLoader.MAX_LINE_CHARS + 500)

        val preview = loaderFor(content).preview(source)!!

        preview.lines.single().length shouldBe TextPreviewLoader.MAX_LINE_CHARS
        // Not the byte cap: this file is nowhere near it, and naming it would send the reader
        // looking for a megabyte that was never there.
        preview.truncation shouldBe TextPreview.Truncation.LineWidth(TextPreviewLoader.MAX_LINE_CHARS)
    }

    // ==================== charset-aware trimming ====================

    /**
     * Every high byte of a single-byte encoding looks like a UTF-8 continuation byte. A backoff that
     * does not check for a real lead byte would trim this whole line away.
     */
    @Test
    fun `a latin-1 line longer than the cap is not trimmed away`() = runTest2 {
        val content = ByteArray(TextPreviewLoader.MAX_PREVIEW_BYTES + 500) { 0xA3.toByte() }

        val preview = loaderFor(content).preview(source)!!

        preview.isTruncated shouldBe true
        preview.charset shouldBe Charsets.ISO_8859_1
        preview.lines.single().length shouldBe TextPreviewLoader.MAX_LINE_CHARS
        preview.lines.single().all { it == '£' } shouldBe true
    }

    /** No newline anywhere, so the byte cut lands inside a character and has to be backed off. */
    @Test
    fun `a single utf-8 line longer than the cap keeps whole characters`() = runTest2 {
        val content = "世".repeat(TextPreviewLoader.MAX_PREVIEW_BYTES).toByteArray()

        val preview = loaderFor(content).preview(source)!!

        preview.isTruncated shouldBe true
        preview.charset shouldBe Charsets.UTF_8
        preview.lines.single().all { it == '世' } shouldBe true
    }

    @Test
    fun `utf-16 is decoded as itself rather than cut on a byte`() = runTest2 {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val content = bom + "alpha\nbravo".toByteArray(Charsets.UTF_16LE)

        val preview = loaderFor(content).preview(source)!!

        preview.charset shouldBe Charsets.UTF_16LE
        preview.lines shouldBe listOf("alpha", "bravo")
    }

    // ==================== GB18030 ====================

    private val gbk = "你好世界，这是一个中文测试文件。\n"

    @Test
    fun `a gbk file is detected as gb18030 and reads as chinese`() = runTest2 {
        val content = gbk.repeat(3).toByteArray(TextCharsets.GB18030)

        val preview = loaderFor(content).preview(source)!!

        preview.charset shouldBe TextCharsets.GB18030
        preview.lines.first() shouldBe gbk.trimEnd('\n')
    }

    /** The cap lands inside a character of a line with no break to cut back to. */
    @Test
    fun `a gbk line longer than the cap keeps whole characters`() = runTest2 {
        // One ASCII byte up front puts the two-byte characters on odd offsets, so the even cap
        // always splits one.
        val content = ("a" + "你好世界".repeat(TextPreviewLoader.MAX_PREVIEW_BYTES / 8 + 100))
            .toByteArray(TextCharsets.GB18030)
        (TextPreviewLoader.MAX_PREVIEW_BYTES - 1) % 2 shouldBe 1

        val preview = loaderFor(content).preview(source)!!

        preview.isTruncated shouldBe true
        preview.charset shouldBe TextCharsets.GB18030
        preview.lines.single().contains('�') shouldBe false
        preview.lines.single().drop(1).all { it in "你好世界" } shouldBe true
    }

    /** Shorter than the probe's head, so nothing was cut and nothing may be trimmed off its end. */
    @Test
    fun `the probe accepts a short gbk file`() = runTest2 {
        // Ends on 好 (BA C3), whose trail byte looks like the lead of a two-byte UTF-8 sequence.
        val content = (gbk.repeat(2) + "你好").toByteArray(TextCharsets.GB18030)

        loaderFor(content).probe(source) shouldBe true
        loaderFor(content).preview(source)!!.charset shouldBe TextCharsets.GB18030
    }

    // ==================== an explicit charset ====================

    @Test
    fun `gb18030 picked for a latin-1 file reads its bytes as chinese`() = runTest2 {
        // C4 E3 BA C3: "ÄãºÃ" in Latin-1, 你好 in GB18030.
        val content = "ÄãºÃ\n".toByteArray(Charsets.ISO_8859_1)
        loaderFor(content).preview(source)!!.charset shouldBe Charsets.ISO_8859_1

        val preview = loaderFor(content).preview(source, TextCharsets.GB18030)!!

        preview.charset shouldBe TextCharsets.GB18030
        preview.lines.first() shouldBe "你好"
    }

    @Test
    fun `latin-1 picked for a gbk file reads its bytes as latin-1`() = runTest2 {
        val content = gbk.repeat(3).toByteArray(TextCharsets.GB18030)

        val preview = loaderFor(content).preview(source, Charsets.ISO_8859_1)!!

        preview.charset shouldBe Charsets.ISO_8859_1
        preview.lines.first() shouldBe String(gbk.trimEnd('\n').toByteArray(TextCharsets.GB18030), Charsets.ISO_8859_1)
    }

    /** Without a BOM detection calls UTF-16 binary, so picking it is the only way to read it. */
    @Test
    fun `utf-16 picked for a bom-less file past the cap keeps whole code units`() = runTest2 {
        // 44 bytes per line: "x", two-unit 😀 ten times, LF. The cap ends 12 bytes into a line,
        // between the surrogate halves of its third 😀.
        val line = "x" + "😀".repeat(10) + "\n"
        val content = line.repeat(TextPreviewLoader.MAX_PREVIEW_BYTES / 44 + 10).toByteArray(Charsets.UTF_16LE)
        TextPreviewLoader.MAX_PREVIEW_BYTES % 44 shouldBe 12
        loaderFor(content).preview(source) shouldBe null

        val preview = loaderFor(content).preview(source, Charsets.UTF_16LE)!!

        preview.isTruncated shouldBe true
        preview.charset shouldBe Charsets.UTF_16LE
        preview.lines.none { it.contains('�') } shouldBe true
        preview.lines.last() shouldBe "x😀😀"
    }

    @Test
    fun `a pick that disagrees with the bom keeps the bom as text`() = runTest2 {
        val content = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()

        val preview = loaderFor(content).preview(source, Charsets.ISO_8859_1)!!

        preview.charset shouldBe Charsets.ISO_8859_1
        preview.lines.single() shouldBe "ï»¿hello"
    }

    /** A UTF-16 BOM would keep a UTF-16 trim, which does not look for line breaks. */
    @Test
    fun `a pick that disagrees with the bom trims as the pick`() = runTest2 {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        // 2 + 4n bytes, so the cap ends two bytes into a line: "ab" under a UTF-16 trim.
        val content = bom + "abc\n".repeat(TextPreviewLoader.MAX_PREVIEW_BYTES / 4 + 10).toByteArray()
        (TextPreviewLoader.MAX_PREVIEW_BYTES - 2) % 4 shouldBe 2

        val preview = loaderFor(content).preview(source, Charsets.ISO_8859_1)!!

        preview.isTruncated shouldBe true
        preview.lines.first() shouldBe "ÿþabc"
        preview.lines.last() shouldBe "abc"
    }

    // ==================== binary and empty ====================

    @Test
    fun `binary content has no preview`() = runTest2 {
        val content = ByteArray(64) { (0x01 + (it % 8)).toByte() }

        loaderFor(content).preview(source) shouldBe null
    }

    @Test
    fun `an empty file previews as empty rather than failing`() = runTest2 {
        val preview = loaderFor(ByteArray(0)).preview(source)!!

        preview.lines shouldBe listOf("")
        preview.isTruncated shouldBe false
    }

    // ==================== the probe ====================

    @Test
    fun `the probe accepts text and rejects binary`() = runTest2 {
        loaderFor("hello\nworld").probe(source) shouldBe true
        loaderFor(ByteArray(64) { (0x01 + (it % 8)).toByte() }).probe(source) shouldBe false
    }

    @Test
    fun `the probe accepts a file far larger than the cap`() = runTest2 {
        val content = "line\n".repeat(TextPreviewLoader.MAX_PREVIEW_BYTES)

        loaderFor(content).probe(source) shouldBe true
    }

    /**
     * The probe's own cut must not decide the verdict. Four-byte characters and no line breaks put a
     * split sequence at the end of every fixed-size head, and an untrimmed decode of that reports
     * perfectly good text as binary.
     */
    @Test
    fun `the probe accepts wide characters whose sequence its cap splits`() = runTest2 {
        val content = "😀".repeat(TextPreviewLoader.MAX_PREVIEW_BYTES / 4)

        loaderFor(content).probe(source) shouldBe true
        loaderFor(content).preview(source)!!.lines.single().isNotEmpty() shouldBe true
    }
}

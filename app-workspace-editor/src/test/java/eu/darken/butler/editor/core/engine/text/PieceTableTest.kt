package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.editor.core.engine.LineEnding
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.random.Random

class PieceTableTest : BaseTest() {

    private suspend fun stringTable(
        content: String,
        compactionThreshold: Int = PieceTable.DEFAULT_COMPACTION_THRESHOLD_CHARS,
    ): PieceTable = PieceTable.create(
        original = StringOriginalDocument(content),
        assertions = true,
        compactionThresholdChars = compactionThreshold,
    )

    private suspend fun blockTable(content: String, blockSize: Int = 8): PieceTable {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val index = BlockIndexBuilder(blockSize).build(Buffer().write(bytes), Charsets.UTF_8)
        val doc = BlockOriginalDocument(index, Charsets.UTF_8, maxCachedBlocks = 2) { start, len ->
            bytes.copyOfRange(start.toInt(), start.toInt() + len)
        }
        return PieceTable.create(doc, assertions = true)
    }

    private fun withBothBackings(content: String, body: suspend (PieceTable) -> Unit) = runTest {
        body(stringTable(content))
        body(blockTable(content))
    }

    private suspend fun PieceTable.shouldMatch(reference: CharSequence) {
        val text = reference.toString()
        readAll() shouldBe text
        totalCharLength shouldBe text.length.toLong()
        totalLineBreaks shouldBe TextMetrics.countBreaks(text).toLong()
        endsWithBreak shouldBe TextMetrics.endsWithBreak(text)
    }

    /** [StringOriginalDocument] whose [charToByte] can be toggled to throw, mimicking a vanished file. */
    private class ToggleFailOriginal(private val delegate: StringOriginalDocument) : OriginalDocument by delegate {
        var fail = false
        override suspend fun charToByte(charOffset: Long): Long {
            if (fail) throw java.io.IOException("backing gone")
            return delegate.charToByte(charOffset)
        }
    }

    @Test
    fun `checkpoint then restore round-trips a mutated table`() = runTest {
        val table = stringTable("hello world")
        val checkpoint = table.checkpoint()

        table.insert(5, " brave new")
        table.delete(0, 5)
        table.readAll() shouldBe " brave new world"

        table.restore(checkpoint)
        table.shouldMatch("hello world")
    }

    @Test
    fun `a mid-batch mutation failure restores fully via checkpoint`() = runTest {
        val original = ToggleFailOriginal(StringOriginalDocument("aXaYaZa"))
        val table = PieceTable.create(original, assertions = true)
        val checkpoint = table.checkpoint()

        // First replacement lands; the second reads the (now-gone) original and throws
        table.delete(5, 6)
        table.insert(5, "!")
        original.fail = true
        runCatching { table.delete(1, 2) }.isFailure shouldBe true

        table.restore(checkpoint)
        original.fail = false
        table.shouldMatch("aXaYaZa")
    }

    // ========== Insert splits ==========

    @Test
    fun `insert at piece start`() = withBothBackings("Hello World") { table ->
        table.insert(0, "XX")
        table.shouldMatch("XXHello World")
    }

    @Test
    fun `insert at piece middle`() = withBothBackings("Hello World") { table ->
        table.insert(5, "XX")
        table.shouldMatch("HelloXX World")
    }

    @Test
    fun `insert at piece end`() = withBothBackings("Hello World") { table ->
        table.insert(11, "XX")
        table.shouldMatch("Hello WorldXX")
    }

    @Test
    fun `insert into empty document`() = withBothBackings("") { table ->
        table.totalCharLength shouldBe 0L
        table.insert(0, "Hello")
        table.shouldMatch("Hello")
    }

    @Test
    fun `multiple inserts at scattered offsets`() = withBothBackings("0123456789") { table ->
        val reference = StringBuilder("0123456789")
        table.insert(5, "AAA")
        reference.insert(5, "AAA")
        table.insert(0, "B")
        reference.insert(0, "B")
        table.insert(reference.length.toLong(), "C")
        reference.insert(reference.length, "C")
        table.insert(7, "D\nE")
        reference.insert(7, "D\nE")
        table.shouldMatch(reference)
    }

    @Test
    fun `read sub ranges after edits`() = withBothBackings("Hello World") { table ->
        table.insert(5, "XX")
        val reference = "HelloXX World"
        for (start in 0..reference.length) {
            for (end in start..reference.length) {
                table.read(start.toLong(), end.toLong()) shouldBe reference.substring(start, end)
            }
        }
    }

    // ========== Delete ==========

    @Test
    fun `delete within single piece`() = withBothBackings("Hello World") { table ->
        table.delete(2, 5)
        table.shouldMatch("He World")
    }

    @Test
    fun `delete across pieces`() = withBothBackings("Hello World") { table ->
        table.insert(5, "XYZ")
        // Spans original left part, the added piece, and original right part
        table.delete(3, 10)
        table.shouldMatch(StringBuilder("Hello World").insert(5, "XYZ").delete(3, 10))
    }

    @Test
    fun `delete whole document`() = withBothBackings("Hello\nWorld") { table ->
        table.delete(0, table.totalCharLength)
        table.shouldMatch("")
        table.pieceCount shouldBe 0
    }

    @Test
    fun `delete empty range is a no-op`() = withBothBackings("Hello") { table ->
        table.delete(3, 3)
        table.shouldMatch("Hello")
    }

    @Test
    fun `delete rejoins remaining pieces correctly`() = withBothBackings("abcdef") { table ->
        table.insert(3, "123")
        table.delete(3, 6)
        table.shouldMatch("abcdef")
    }

    // ========== Coalescing ==========

    @Test
    fun `sequential typing coalesces into one added piece`() = withBothBackings("Hello World") { table ->
        table.insert(5, "a")
        val piecesAfterFirst = table.pieceCount
        table.insert(6, "b")
        table.insert(7, "c")
        table.insert(8, "d")
        table.pieceCount shouldBe piecesAfterFirst
        table.shouldMatch("Helloabcd World")
    }

    @Test
    fun `typing at document end coalesces`() = withBothBackings("Hi") { table ->
        table.insert(2, "a")
        table.insert(3, "b")
        table.insert(4, "c")
        table.pieceCount shouldBe 2
        table.shouldMatch("Hiabc")
    }

    @Test
    fun `non-tail insert does not coalesce but stays correct`() = withBothBackings("Hello") { table ->
        table.insert(5, "abc")
        table.insert(5, "X")
        table.shouldMatch("HelloXabc")
    }

    // ========== Surrogate pairs ==========

    @Test
    fun `insert mid surrogate pair materializes the code point`() = withBothBackings("ab😀cd") { table ->
        val reference = StringBuilder("ab😀cd")
        table.insert(3, "X")
        reference.insert(3, "X")
        table.shouldMatch(reference)
        table.pieceSnapshot().count { it is Piece.Added } shouldBe 3
    }

    @Test
    fun `delete ending mid surrogate pair keeps the high half`() = withBothBackings("ab😀cd") { table ->
        val reference = StringBuilder("ab😀cd")
        table.delete(3, 5)
        reference.delete(3, 5)
        table.shouldMatch(reference)
    }

    @Test
    fun `delete starting mid surrogate pair keeps the low half`() = withBothBackings("ab😀cd") { table ->
        val reference = StringBuilder("ab😀cd")
        table.delete(2, 3)
        reference.delete(2, 3)
        table.shouldMatch(reference)
    }

    @Test
    fun `split at code point boundary next to pair does not materialize`() =
        withBothBackings("ab😀cd") { table ->
            table.insert(2, "X")
            table.shouldMatch("abX😀cd")
            table.insert(5, "Y")
            table.shouldMatch("abX😀Ycd")
        }

    @Test
    fun `consecutive pairs split between them`() = withBothBackings("😀😁") { table ->
        table.insert(2, "X")
        table.shouldMatch("😀X😁")
    }

    // ========== CRLF across pieces ==========

    @Test
    fun `insert between CR and LF creates two breaks`() = withBothBackings("a\r\nb") { table ->
        table.totalLineBreaks shouldBe 1L
        table.insert(2, "X")
        table.shouldMatch("a\rX\nb")
        table.totalLineBreaks shouldBe 2L
    }

    @Test
    fun `deleting the insert between CR and LF rejoins the break`() = withBothBackings("a\r\nb") { table ->
        table.insert(2, "X")
        table.delete(2, 3)
        table.shouldMatch("a\r\nb")
        table.totalLineBreaks shouldBe 1L
    }

    @Test
    fun `delete CR of a CRLF leaves one break`() = withBothBackings("a\r\nb") { table ->
        table.delete(1, 2)
        table.shouldMatch("a\nb")
        table.totalLineBreaks shouldBe 1L
    }

    @Test
    fun `delete LF of a CRLF leaves one break`() = withBothBackings("a\r\nb") { table ->
        table.delete(2, 3)
        table.shouldMatch("a\rb")
        table.totalLineBreaks shouldBe 1L
    }

    @Test
    fun `inserted CR joins with following LF piece`() = withBothBackings("a\nb") { table ->
        table.insert(1, "x\r")
        table.shouldMatch("ax\r\nb")
        table.totalLineBreaks shouldBe 1L
    }

    @Test
    fun `inserted LF joins with preceding CR piece`() = withBothBackings("a\rb") { table ->
        table.insert(2, "\nx")
        table.shouldMatch("a\r\nxb")
        table.totalLineBreaks shouldBe 1L
    }

    // ========== Line lookup ==========

    @Test
    fun `line offsets over mixed endings`() = withBothBackings("l0\nl1\r\nl2\rl3") { table ->
        table.totalLineBreaks shouldBe 3L
        table.lineStartOffset(0) shouldBe 0L
        table.lineStartOffset(1) shouldBe 3L
        table.lineStartOffset(2) shouldBe 7L
        table.lineStartOffset(3) shouldBe 10L

        table.lineOfOffset(0) shouldBe 0L
        table.lineOfOffset(2) shouldBe 0L
        table.lineOfOffset(3) shouldBe 1L
        // Between the CR and LF of a CRLF: the break has not ended yet
        table.lineOfOffset(6) shouldBe 1L
        table.lineOfOffset(7) shouldBe 2L
        table.lineOfOffset(9) shouldBe 2L
        table.lineOfOffset(10) shouldBe 3L
        table.lineOfOffset(12) shouldBe 3L
    }

    @Test
    fun `line offsets after seam edits`() = withBothBackings("a\r\nb\r\nc") { table ->
        table.insert(2, "X")
        // "a\rX\nb\r\nc" - breaks end at 2 (lone CR), 4 (LF), 7 (CRLF)
        table.lineStartOffset(0) shouldBe 0L
        table.lineStartOffset(1) shouldBe 2L
        table.lineStartOffset(2) shouldBe 4L
        table.lineStartOffset(3) shouldBe 7L
        table.lineOfOffset(1) shouldBe 0L
        table.lineOfOffset(2) shouldBe 1L
        table.lineOfOffset(4) shouldBe 2L
        table.lineOfOffset(6) shouldBe 2L
        table.lineOfOffset(7) shouldBe 3L
    }

    @Test
    fun `empty document line semantics`() = withBothBackings("") { table ->
        table.totalLineBreaks shouldBe 0L
        table.lineStartOffset(0) shouldBe 0L
        table.lineOfOffset(0) shouldBe 0L
        table.endsWithBreak shouldBe false
    }

    // ========== Compaction ==========

    @Test
    fun `compaction reclaims garbage and preserves content`() = runTest {
        val table = stringTable("base", compactionThreshold = 64)
        val reference = StringBuilder("base")
        val paste = "0123456789".repeat(10)

        table.insert(2, paste)
        reference.insert(2, paste)
        table.shouldMatch(reference)

        table.delete(2, 2L + 90)
        reference.delete(2, 2 + 90)
        table.shouldMatch(reference)
        table.addBufferLength shouldBeLessThanOrEqual 64

        // Undo/redo shaped re-edits after remapping
        val deleted = paste.substring(0, 90)
        table.insert(2, deleted)
        reference.insert(2, deleted)
        table.shouldMatch(reference)

        table.delete(2, 2L + 90)
        reference.delete(2, 2 + 90)
        table.shouldMatch(reference)
    }

    @Test
    fun `compaction with interleaved live pieces`() = runTest {
        val table = stringTable("0123456789", compactionThreshold = 32)
        val reference = StringBuilder("0123456789")
        for (i in 0 until 6) {
            val text = "ins$i-"
            val offset = (i * 2).toLong()
            table.insert(offset, text)
            reference.insert(i * 2, text)
        }
        table.delete(5, 25)
        reference.delete(5, 25)
        table.shouldMatch(reference)
        table.insert(3, "Z".repeat(40))
        reference.insert(3, "Z".repeat(40))
        table.shouldMatch(reference)
    }

    // ========== Fuzzing against a reference model ==========

    @Test
    fun `randomized edits match reference model`() = runTest {
        val alphabet = listOf("a", "b", "Z", "\n", "\r", "\r\n", "中", "😀", "xy\nz")
        val random = Random(42)
        val initial = buildString { repeat(120) { append(alphabet[random.nextInt(alphabet.size)]) } }

        for (table in listOf(stringTable(initial, compactionThreshold = 128), blockTable(initial))) {
            val reference = StringBuilder(initial)
            repeat(250) { step ->
                if (reference.isEmpty() || random.nextBoolean()) {
                    val offset = random.nextInt(reference.length + 1)
                    val text = buildString {
                        repeat(1 + random.nextInt(4)) { append(alphabet[random.nextInt(alphabet.size)]) }
                    }
                    table.insert(offset.toLong(), text)
                    reference.insert(offset, text)
                } else {
                    val start = random.nextInt(reference.length + 1)
                    val end = start + random.nextInt(reference.length - start + 1)
                    table.delete(start.toLong(), end.toLong())
                    reference.delete(start, end)
                }
                table.readAll() shouldBe reference.toString()
                table.totalLineBreaks shouldBe TextMetrics.countBreaks(reference).toLong()

                if (step % 25 == 0 && reference.isNotEmpty()) {
                    val offset = random.nextInt(reference.length + 1)
                    table.lineOfOffset(offset.toLong()) shouldBe referenceLineOfOffset(reference.toString(), offset)
                    val breaks = TextMetrics.countBreaks(reference)
                    if (breaks > 0) {
                        val line = 1 + random.nextInt(breaks)
                        table.lineStartOffset(line.toLong()) shouldBe
                            TextMetrics.endOfNthBreak(reference, line).toLong()
                    }
                }
            }
        }
    }

    @Test
    fun `line math stays exact beyond Int MAX line breaks`() = runTest {
        // Virtual ~8.8GB UTF-16LE document of "a\n" pairs: 140k blocks x 16384 breaks ≈ 2.29e9 breaks
        val blockCount = 140_000
        val blockBytes = 64 * 1024
        val blockChars = blockBytes / 2
        val breaksPerBlock = blockChars / 2
        val blocks = buildList {
            var byteStart = 0L
            var charStart = 0L
            repeat(blockCount) {
                add(
                    BlockIndex.Block(
                        byteStart = byteStart,
                        byteLen = blockBytes,
                        charStart = charStart,
                        charCount = blockChars,
                        lineBreakCount = breaksPerBlock,
                        startsWithLf = false,
                        endsWithCr = false,
                        endsWithBreak = true,
                    ),
                )
                byteStart += blockBytes
                charStart += blockChars
            }
        }
        val index = BlockIndex(blocks, LineEnding.LF)
        val doc = BlockOriginalDocument(index, Charsets.UTF_16LE, maxCachedBlocks = 2) { start, len ->
            // Block starts are 4-byte aligned, so char j is 'a' for even j and '\n' for odd j
            ByteArray(len) { i ->
                when ((start + i) % 4) {
                    0L -> 'a'.code.toByte()
                    2L -> '\n'.code.toByte()
                    else -> 0
                }
            }
        }
        val table = PieceTable.create(doc)

        val totalBreaks = blockCount.toLong() * breaksPerBlock
        (totalBreaks > Int.MAX_VALUE.toLong()).shouldBeTrue()
        table.totalLineBreaks shouldBe totalBreaks

        // Line n is the "a" at char 2n; its break is the '\n' at 2n+1
        val hugeLine = 2_200_000_123L
        table.lineStartOffset(hugeLine) shouldBe hugeLine * 2
        table.lineOfOffset(hugeLine * 2) shouldBe hugeLine
        table.lineOfOffset(hugeLine * 2 + 1) shouldBe hugeLine
        table.read(hugeLine * 2, hugeLine * 2 + 2) shouldBe "a\n"
    }

    private fun referenceLineOfOffset(text: String, offset: Int): Long {
        var line = 0L
        var i = 0
        while (i < offset) {
            when (text[i]) {
                '\n' -> {
                    line++
                    i++
                }
                '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') {
                    if (i + 2 <= offset) {
                        line++
                        i += 2
                    } else {
                        i = offset
                    }
                } else {
                    line++
                    i++
                }
                else -> i++
            }
        }
        return line
    }
}

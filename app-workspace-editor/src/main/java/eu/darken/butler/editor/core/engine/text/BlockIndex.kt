package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.editor.core.engine.LineEnding

/**
 * Byte/char/line map of the original file, built once at open by [BlockIndexBuilder].
 * Block edges are snapped to code-point boundaries, so each block's byte range decodes in
 * isolation to exactly its [Block.charCount] chars.
 *
 * [blockDigests] holds a truncated SHA-256 per block, captured for free during the scan and
 * used for sampled external-modification checks at save time. Synthetic indexes built in tests
 * may omit it (zeros); they are never staleness-verified.
 */
class BlockIndex(
    val blocks: List<Block>,
    val lineEnding: LineEnding,
    val blockDigests: LongArray = LongArray(blocks.size),
) {

    init {
        require(blockDigests.size == blocks.size) {
            "Digest count ${blockDigests.size} != block count ${blocks.size}"
        }
    }

    data class Block(
        val byteStart: Long,
        val byteLen: Int,
        val charStart: Long,
        val charCount: Int,
        val lineBreakCount: Int,
        val startsWithLf: Boolean,
        val endsWithCr: Boolean,
        val endsWithBreak: Boolean,
    )

    val byteLength: Long = blocks.lastOrNull()?.let { it.byteStart + it.byteLen } ?: 0L
    val charLength: Long = blocks.lastOrNull()?.let { it.charStart + it.charCount } ?: 0L
    val endsWithBreak: Boolean = blocks.lastOrNull()?.endsWithBreak ?: false

    // A CRLF straddling a block edge is attributed to the block holding its '\r'
    private val breaksBefore = LongArray(blocks.size + 1).also { arr ->
        for (i in blocks.indices) {
            arr[i + 1] = arr[i] + correctedBreakCount(i)
        }
    }

    val lineBreakCount: Long = breaksBefore[blocks.size]

    fun correctedBreakCount(index: Int): Long {
        val block = blocks[index]
        val joined = index > 0 && blocks[index - 1].endsWithCr && block.startsWithLf
        return block.lineBreakCount - (if (joined) 1L else 0L)
    }

    fun breaksBeforeBlock(index: Int): Long = breaksBefore[index]

    /** Index of the block containing [charOffset]; requires `0 <= charOffset < charLength`. */
    fun blockForChar(charOffset: Long): Int {
        require(charOffset in 0 until charLength) { "Char offset $charOffset not in [0, $charLength)" }
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blocks[mid].charStart <= charOffset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Index of the block containing the [n]th attributed break, 1-based [n]. */
    fun blockForLineBreak(n: Long): Int {
        require(n in 1..lineBreakCount) { "Break ordinal $n not in [1, $lineBreakCount]" }
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (breaksBefore[mid + 1] >= n) hi = mid else lo = mid + 1
        }
        return lo
    }
}

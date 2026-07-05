package eu.darken.butler.editor.core.engine.text

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * [OriginalDocument] over a [BlockIndex] plus a [DecodeCache] of decoded blocks.
 * [blockReader] reads logical (post-BOM) byte ranges; the creator adds any BOM size.
 */
class BlockOriginalDocument(
    private val index: BlockIndex,
    private val charset: Charset,
    maxCachedBlocks: Int = DecodeCache.DEFAULT_MAX_BLOCKS,
    private val blockReader: suspend (byteStart: Long, byteLen: Int) -> ByteArray,
) : OriginalDocument {

    private val cache = DecodeCache(maxCachedBlocks) { blockIndex ->
        val block = index.blocks[blockIndex]
        val bytes = blockReader(block.byteStart, block.byteLen)
        check(bytes.size == block.byteLen) {
            "Block $blockIndex read ${bytes.size} bytes, expected ${block.byteLen}"
        }
        val text = decodeBlock(bytes)
        check(text.length == block.charCount) {
            "Block $blockIndex decoded ${text.length} chars, expected ${block.charCount}"
        }
        text
    }

    override val charLength: Long = index.charLength
    override val byteLength: Long = index.byteLength
    override val lineBreakCount: Long = index.lineBreakCount

    private fun decodeBlock(bytes: ByteArray): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val charBuffer = CharBuffer.allocate(bytes.size + 2)
        decoder.decode(ByteBuffer.wrap(bytes), charBuffer, true)
        decoder.flush(charBuffer)
        charBuffer.flip()
        return charBuffer.toString()
    }

    suspend fun clearCache() = cache.clear()

    override suspend fun readChars(charStart: Long, charEnd: Long): String {
        checkRange(charStart, charEnd)
        if (charStart == charEnd) return ""
        require(charEnd - charStart <= Int.MAX_VALUE) { "Read range too large: $charStart-$charEnd" }
        val result = StringBuilder((charEnd - charStart).toInt())
        var blockIndex = index.blockForChar(charStart)
        var pos = charStart
        while (pos < charEnd) {
            val block = index.blocks[blockIndex]
            val text = cache.get(blockIndex)
            val local = (pos - block.charStart).toInt()
            val take = minOf(block.charCount.toLong() - local, charEnd - pos).toInt()
            result.append(text, local, local + take)
            pos += take
            blockIndex++
        }
        return result.toString()
    }

    override suspend fun charToByte(charOffset: Long): Long {
        require(charOffset in 0..charLength) { "Char offset $charOffset not in [0, $charLength]" }
        if (charOffset == charLength) return byteLength
        val blockIndex = index.blockForChar(charOffset)
        val block = index.blocks[blockIndex]
        val local = (charOffset - block.charStart).toInt()
        if (local == 0) return block.byteStart
        val bytes = blockReader(block.byteStart, block.byteLen)
        return block.byteStart + TextMetrics.charToByteInBlock(bytes, charset, local)
    }

    override suspend fun countLineBreaks(charStart: Long, charEnd: Long): Long {
        checkRange(charStart, charEnd)
        if (charStart == charEnd) return 0L
        val first = index.blockForChar(charStart)
        val lastInclusive = index.blockForChar(charEnd - 1)
        var total = 0L
        var prevEndsWithCr = false
        for (i in first..lastInclusive) {
            val part = partMetrics(i, charStart, charEnd)
            total += part.breakCount
            if (i > first && prevEndsWithCr && part.startsWithLf) total--
            prevEndsWithCr = part.endsWithCr
        }
        return total
    }

    override suspend fun findNthLineBreak(charStart: Long, charEnd: Long, n: Long): Long {
        checkRange(charStart, charEnd)
        require(n >= 1) { "Break ordinal must be >= 1, was $n" }
        require(charStart < charEnd) { "Empty range has no breaks" }
        val firstBlock = index.blockForChar(charStart)
        val lastInclusive = index.blockForChar(charEnd - 1)

        var walkStart = firstBlock
        var seen = 0L
        var prevEndsWithCr = false
        if (charStart == 0L && n <= index.lineBreakCount) {
            // Common case (piece starting at document begin): jump via the corrected prefix sums.
            // Blocks before the candidate are full parts of the range, so their attributed
            // counts equal the index's corrected prefix.
            val candidate = index.blockForLineBreak(n)
            if (candidate in (firstBlock + 1)..lastInclusive) {
                walkStart = candidate
                seen = index.breaksBeforeBlock(candidate)
                prevEndsWithCr = index.blocks[candidate - 1].endsWithCr
            }
        }

        for (i in walkStart..lastInclusive) {
            val block = index.blocks[i]
            val part = partMetrics(i, charStart, charEnd)
            val joined = i != firstBlock && prevEndsWithCr && part.startsWithLf
            val attributed = part.breakCount - (if (joined) 1L else 0L)
            if (seen + attributed >= n) {
                // When joined, the part's leading '\n' belongs to the previous part's break
                val localOrdinal = (n - seen + (if (joined) 1L else 0L)).toInt()
                val text = cache.get(i)
                val lo = (maxOf(charStart, block.charStart) - block.charStart).toInt()
                val hi = (minOf(charEnd, block.charStart + block.charCount) - block.charStart).toInt()
                val endLocal = TextMetrics.endOfNthBreak(text, localOrdinal, lo, hi)
                var endDoc = block.charStart + lo + endLocal
                if (endDoc < charEnd && text[lo + endLocal - 1] == '\r' && readChars(endDoc, endDoc + 1) == "\n") {
                    endDoc++
                }
                return endDoc
            }
            seen += attributed
            prevEndsWithCr = part.endsWithCr
        }
        throw IllegalArgumentException("Range has only $seen breaks, requested $n")
    }

    private class PartMetrics(
        val breakCount: Long,
        val startsWithLf: Boolean,
        val endsWithCr: Boolean,
    )

    private suspend fun partMetrics(blockIndex: Int, charStart: Long, charEnd: Long): PartMetrics {
        val block = index.blocks[blockIndex]
        val from = maxOf(charStart, block.charStart)
        val to = minOf(charEnd, block.charStart + block.charCount)
        return if (from == block.charStart && to == block.charStart + block.charCount) {
            PartMetrics(block.lineBreakCount.toLong(), block.startsWithLf, block.endsWithCr)
        } else {
            val text = cache.get(blockIndex)
            val lo = (from - block.charStart).toInt()
            val hi = (to - block.charStart).toInt()
            PartMetrics(
                TextMetrics.countBreaks(text, lo, hi).toLong(),
                text[lo] == '\n',
                text[hi - 1] == '\r',
            )
        }
    }

    private fun checkRange(charStart: Long, charEnd: Long) {
        require(charStart in 0..charEnd && charEnd <= charLength) {
            "Invalid char range: $charStart-$charEnd (length $charLength)"
        }
    }
}

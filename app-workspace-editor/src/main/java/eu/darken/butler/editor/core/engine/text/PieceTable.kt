package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag

/**
 * Piece-table document: an ordered piece sequence over the immutable [original] document and an
 * append-only add buffer. Edits split pieces and insert new ones; content is never mutated, so
 * decoded-block caches stay pure caches.
 *
 * All offsets are UTF-16 code units. Piece splits inside an [Piece.Original] snap to code-point
 * boundaries: a split between surrogate halves materializes that code point into the add buffer
 * (structure changes, document content and offsets don't). Splitting [Piece.Added] anywhere is
 * fine — a mid-pair insert legitimately produces lone surrogates (parity with the old engine).
 *
 * Not thread-safe; callers serialize access (the document buffer holds one mutex around each op).
 * Storage is a flat list with prefix sums rebuilt per edit — O(pieces), kept private so a
 * balanced tree can replace it without API changes.
 */
class PieceTable private constructor(
    private val original: OriginalDocument,
    private val assertions: Boolean,
    private val compactionThresholdChars: Int,
) {

    private val pieces = mutableListOf<Piece>()
    private var addBuffer = StringBuilder()
    private var charStarts = LongArray(1)
    private var breakStarts = LongArray(1)
    private var expectedCharLength = 0L

    val totalCharLength: Long get() = charStarts[pieces.size]

    /** Universal breaks with CRLF joins across piece seams counted once. */
    val totalLineBreaks: Long get() = breakStarts[pieces.size]

    val endsWithBreak: Boolean get() = pieces.lastOrNull()?.endsWithBreak ?: false

    val pieceCount: Int get() = pieces.size

    val addBufferLength: Int get() = addBuffer.length

    internal fun pieceSnapshot(): List<Piece> = pieces.toList()

    suspend fun insert(offset: Long, text: String) {
        require(offset in 0..totalCharLength) { "Insert offset $offset not in [0, $totalCharLength]" }
        if (text.isEmpty()) return
        expectedCharLength += text.length

        val endingPiece = pieceEndingAt(offset)
        val coalesced = endingPiece?.let { pieceIndex ->
            val prev = pieces[pieceIndex] as? Piece.Added ?: return@let false
            if (prev.addStart + prev.addLen != addBuffer.length) return@let false
            addBuffer.append(text)
            val joined = prev.endsWithCr && text[0] == '\n'
            pieces[pieceIndex] = prev.copy(
                addLen = prev.addLen + text.length,
                lineBreakCount = prev.lineBreakCount + TextMetrics.countBreaks(text) - (if (joined) 1L else 0L),
                endsWithCr = TextMetrics.endsWithCr(text),
                endsWithBreak = TextMetrics.endsWithBreak(text),
            )
            true
        } ?: false

        if (!coalesced) {
            val insertAt = splitAt(offset)
            val addStart = addBuffer.length
            addBuffer.append(text)
            pieces.add(insertAt, makeAdded(addStart, text.length))
        }
        afterEdit()
    }

    suspend fun delete(start: Long, end: Long) {
        require(start in 0..end && end <= totalCharLength) {
            "Delete range $start-$end not in [0, $totalCharLength]"
        }
        if (start == end) return
        expectedCharLength -= end - start

        val from = splitAt(start)
        val to = splitAt(end)
        repeat(to - from) { pieces.removeAt(from) }
        afterEdit()
    }

    suspend fun read(start: Long, end: Long): String {
        require(start in 0..end && end <= totalCharLength) {
            "Read range $start-$end not in [0, $totalCharLength]"
        }
        if (start == end) return ""
        require(end - start <= Int.MAX_VALUE) { "Read range too large: $start-$end" }
        val result = StringBuilder((end - start).toInt())
        var i = pieceIndexForOffset(start)
        var pos = start
        while (pos < end) {
            val pieceStart = charStarts[i]
            val from = pos - pieceStart
            val to = minOf(pieces[i].charCount, end - pieceStart)
            result.append(pieceChars(i, from, to))
            pos = pieceStart + to
            i++
        }
        return result.toString()
    }

    suspend fun readAll(): String = read(0, totalCharLength)

    /** Char offset where line [line] starts; line 0 starts at 0, line L after the Lth break. */
    suspend fun lineStartOffset(line: Long): Long {
        require(line in 0..totalLineBreaks) { "Line $line not in [0, $totalLineBreaks]" }
        if (line == 0L) return 0L
        val n = line
        val pieceIndex = pieceForBreak(n)
        val piece = pieces[pieceIndex]
        val joined = pieceIndex > 0 && pieces[pieceIndex - 1].endsWithCr && piece.startsWithLf
        val localOrdinal = n - breakStarts[pieceIndex] + (if (joined) 1L else 0L)
        val endLocal = when (piece) {
            is Piece.Added -> TextMetrics.endOfNthBreak(
                addBuffer,
                localOrdinal.toInt(),
                piece.addStart,
                piece.addStart + piece.addLen,
            ).toLong()
            is Piece.Original -> original.findNthLineBreak(
                piece.charStart,
                piece.charStart + piece.charCount,
                localOrdinal,
            ) - piece.charStart
        }
        var endDoc = charStarts[pieceIndex] + endLocal
        // Seam CRLF: the break's '\r' ends this piece and its '\n' starts the next
        if (endLocal == piece.charCount &&
            piece.endsWithCr &&
            pieceIndex + 1 < pieces.size &&
            pieces[pieceIndex + 1].startsWithLf
        ) {
            endDoc++
        }
        return endDoc
    }

    /** Number of breaks ending at or before [offset] == the line containing [offset]. */
    suspend fun lineOfOffset(offset: Long): Long {
        require(offset in 0..totalCharLength) { "Offset $offset not in [0, $totalCharLength]" }
        if (offset == 0L || pieces.isEmpty()) return 0L
        if (offset == totalCharLength) return totalLineBreaks
        val i = pieceIndexForOffset(offset)
        val piece = pieces[i]
        val local = offset - charStarts[i]
        val joined = i > 0 && pieces[i - 1].endsWithCr && piece.startsWithLf
        if (local == 0L) {
            // A seam CRLF attributed to the previous piece ends inside this one (after its '\n')
            return breakStarts[i] - (if (joined) 1L else 0L)
        }
        val isolated = when (piece) {
            is Piece.Added -> TextMetrics.countBreaks(addBuffer, piece.addStart, piece.addStart + local.toInt()).toLong()
            is Piece.Original -> original.countLineBreaks(piece.charStart, piece.charStart + local)
        }
        // A '\r' just before offset followed by '\n' at offset is a break ending beyond offset
        val crPending = charAt(i, local - 1) == '\r' && charAt(i, local) == '\n'
        return breakStarts[i] + isolated - (if (joined) 1L else 0L) - (if (crPending) 1L else 0L)
    }

    /**
     * Ensures a piece boundary exists at document offset [offset], returning the index of the
     * first piece at/after it. May materialize a surrogate pair into the add buffer.
     */
    private suspend fun splitAt(offset: Long): Int {
        if (offset == 0L) return 0
        if (offset == totalCharLength) return pieces.size
        val i = pieceIndexForOffset(offset)
        val local = offset - charStarts[i]
        if (local == 0L) return i

        val replacement: List<Piece> = when (val piece = pieces[i]) {
            is Piece.Added -> listOf(
                makeAdded(piece.addStart, local.toInt()),
                makeAdded(piece.addStart + local.toInt(), piece.addLen - local.toInt()),
            )
            is Piece.Original -> {
                val before = charAt(i, local - 1)
                val after = charAt(i, local)
                if (before.isHighSurrogate() && after.isLowSurrogate()) {
                    val addStart = addBuffer.length
                    addBuffer.append(before)
                    addBuffer.append(after)
                    buildList {
                        if (local - 1 > 0) add(cutOriginal(piece, 0, local - 1))
                        add(makeAdded(addStart, 1))
                        add(makeAdded(addStart + 1, 1))
                        if (local + 1 < piece.charCount) add(cutOriginal(piece, local + 1, piece.charCount))
                    }
                } else {
                    listOf(
                        cutOriginal(piece, 0, local),
                        cutOriginal(piece, local, piece.charCount),
                    )
                }
            }
        }
        pieces.removeAt(i)
        pieces.addAll(i, replacement)
        rebuildPrefix()
        return pieceIndexForOffset(offset)
    }

    private suspend fun cutOriginal(piece: Piece.Original, from: Long, to: Long): Piece.Original {
        val charStart = piece.charStart + from
        val charEnd = piece.charStart + to
        val byteStart = if (from == 0L) piece.byteStart else original.charToByte(charStart)
        val byteEnd = if (to == piece.charCount) piece.byteStart + piece.byteLen else original.charToByte(charEnd)
        val firstChar = original.readChars(charStart, charStart + 1)[0]
        val lastChar = original.readChars(charEnd - 1, charEnd)[0]
        val breaks = if (from == 0L && to == piece.charCount) {
            piece.lineBreakCount
        } else {
            original.countLineBreaks(charStart, charEnd)
        }
        return Piece.Original(
            byteStart = byteStart,
            byteLen = byteEnd - byteStart,
            charStart = charStart,
            charCount = to - from,
            lineBreakCount = breaks,
            startsWithLf = firstChar == '\n',
            endsWithCr = lastChar == '\r',
            endsWithBreak = lastChar == '\n' || lastChar == '\r',
        )
    }

    private fun makeAdded(addStart: Int, addLen: Int): Piece.Added = Piece.Added(
        addStart = addStart,
        addLen = addLen,
        lineBreakCount = TextMetrics.countBreaks(addBuffer, addStart, addStart + addLen).toLong(),
        startsWithLf = addBuffer[addStart] == '\n',
        endsWithCr = addBuffer[addStart + addLen - 1] == '\r',
        endsWithBreak = addBuffer[addStart + addLen - 1].let { it == '\n' || it == '\r' },
    )

    private suspend fun pieceChars(index: Int, from: Long, to: Long): String = when (val piece = pieces[index]) {
        is Piece.Original -> original.readChars(piece.charStart + from, piece.charStart + to)
        is Piece.Added -> addBuffer.substring(piece.addStart + from.toInt(), piece.addStart + to.toInt())
    }

    private suspend fun charAt(index: Int, local: Long): Char = when (val piece = pieces[index]) {
        is Piece.Added -> addBuffer[piece.addStart + local.toInt()]
        is Piece.Original -> original.readChars(piece.charStart + local, piece.charStart + local + 1)[0]
    }

    /** Index of the piece ending exactly at [offset], or null if none does. */
    private fun pieceEndingAt(offset: Long): Int? = when {
        pieces.isEmpty() || offset == 0L -> null
        offset == totalCharLength -> pieces.size - 1
        else -> {
            val i = pieceIndexForOffset(offset)
            if (charStarts[i] == offset) i - 1 else null
        }
    }

    private fun pieceIndexForOffset(offset: Long): Int {
        var lo = 0
        var hi = pieces.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (charStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Smallest piece index whose attributed breaks reach ordinal [n]. */
    private fun pieceForBreak(n: Long): Int {
        var lo = 0
        var hi = pieces.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (breakStarts[mid + 1] >= n) hi = mid else lo = mid + 1
        }
        return lo
    }

    private fun afterEdit() {
        compactIfNeeded()
        rebuildPrefix()
        checkInvariants()
    }

    private fun rebuildPrefix() {
        charStarts = LongArray(pieces.size + 1)
        breakStarts = LongArray(pieces.size + 1)
        var prev: Piece? = null
        for (i in pieces.indices) {
            val piece = pieces[i]
            val joined = prev != null && prev.endsWithCr && piece.startsWithLf
            charStarts[i + 1] = charStarts[i] + piece.charCount
            breakStarts[i + 1] = breakStarts[i] + piece.lineBreakCount - (if (joined) 1L else 0L)
            prev = piece
        }
    }

    private fun compactIfNeeded() {
        if (addBuffer.length <= compactionThresholdChars) return
        val live = pieces.sumOf { (it as? Piece.Added)?.addLen?.toLong() ?: 0L }
        if (live >= addBuffer.length) return
        val newBuffer = StringBuilder(live.toInt())
        for (i in pieces.indices) {
            val piece = pieces[i] as? Piece.Added ?: continue
            val newStart = newBuffer.length
            newBuffer.append(addBuffer, piece.addStart, piece.addStart + piece.addLen)
            pieces[i] = piece.copy(addStart = newStart)
        }
        log(TAG, WARN) {
            "Compacted add buffer: ${addBuffer.length} -> ${newBuffer.length} chars (${pieces.size} pieces)"
        }
        addBuffer = newBuffer
    }

    /**
     * Cheap structural checks per edit. Original piece break counts are trusted (verifying them
     * needs decoding); black-box tests pin them via full-content comparisons.
     */
    private fun checkInvariants() {
        if (!assertions) return
        check(charStarts[pieces.size] == expectedCharLength) {
            "Char length drift: derived ${charStarts[pieces.size]}, expected $expectedCharLength"
        }
        for (piece in pieces) {
            check(piece.charCount > 0) { "Empty piece: $piece" }
            when (piece) {
                is Piece.Added -> {
                    check(piece.addStart >= 0 && piece.addStart + piece.addLen <= addBuffer.length) {
                        "Added piece outside add buffer: $piece (buffer ${addBuffer.length})"
                    }
                    check(
                        piece.lineBreakCount ==
                            TextMetrics.countBreaks(addBuffer, piece.addStart, piece.addStart + piece.addLen).toLong(),
                    ) { "Added piece break count drift: $piece" }
                    check(piece.startsWithLf == (addBuffer[piece.addStart] == '\n')) { "Added startsWithLf drift: $piece" }
                    check(piece.endsWithCr == (addBuffer[piece.addStart + piece.addLen - 1] == '\r')) {
                        "Added endsWithCr drift: $piece"
                    }
                }
                is Piece.Original -> {
                    check(piece.charStart >= 0 && piece.charStart + piece.charCount <= original.charLength) {
                        "Original piece outside document chars: $piece (length ${original.charLength})"
                    }
                    check(piece.byteStart >= 0 && piece.byteLen >= 0 && piece.byteStart + piece.byteLen <= original.byteLength) {
                        "Original piece outside document bytes: $piece (length ${original.byteLength})"
                    }
                }
            }
        }
    }

    companion object {
        // 16MB of UTF-16 chars
        const val DEFAULT_COMPACTION_THRESHOLD_CHARS = 8 * 1024 * 1024
        private val TAG = logTag("Editor", "Engine", "PieceTable")

        suspend fun create(
            original: OriginalDocument,
            assertions: Boolean = false,
            compactionThresholdChars: Int = DEFAULT_COMPACTION_THRESHOLD_CHARS,
        ): PieceTable {
            val table = PieceTable(original, assertions, compactionThresholdChars)
            if (original.charLength > 0) {
                val firstChar = original.readChars(0, 1)[0]
                val lastChar = original.readChars(original.charLength - 1, original.charLength)[0]
                table.pieces += Piece.Original(
                    byteStart = 0L,
                    byteLen = original.byteLength,
                    charStart = 0L,
                    charCount = original.charLength,
                    lineBreakCount = original.lineBreakCount,
                    startsWithLf = firstChar == '\n',
                    endsWithCr = lastChar == '\r',
                    endsWithBreak = lastChar == '\n' || lastChar == '\r',
                )
                table.expectedCharLength = original.charLength
            }
            table.rebuildPrefix()
            table.checkInvariants()
            return table
        }
    }
}

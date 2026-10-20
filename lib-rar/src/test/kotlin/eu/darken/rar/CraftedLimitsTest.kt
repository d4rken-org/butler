package eu.darken.rar

import eu.darken.rar.RarBuilder.Companion.compression
import eu.darken.rar.RarBuilder.Companion.le32
import eu.darken.rar.RarBuilder.Companion.record
import eu.darken.rar.RarBuilder.Companion.vint
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream

/**
 * Crafted fields beyond a limit or beyond the data that holds them: each gets its specific
 * rejection, and none makes the reader allocate in proportion to the declared value.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CraftedLimitsTest {
    /** Declared sizes that would fit the test heap if the reader allocated them. */
    private val huge = 200L shl 20

    /** What [block] allocates must stay below [bound], where the JVM can tell. */
    private fun assertAllocatesBelow(bound: Long, block: () -> Unit) {
        val allocated = Allocation.of(block) ?: return
        assertTrue(allocated < bound, "allocated $allocated bytes")
    }

    private fun walk(bytes: ByteArray, mode: ReadMode, options: RarOptions = RarOptions()) =
        RarReader(ByteArrayInputStream(bytes), options.forMode(mode)).use { HostileRun.drive(it, mode, ByteArray(8192)) }

    private fun limitOf(e: RarUnsupportedException) = e.reason as RarUnsupportedReason.LimitExceeded

    /** A base block with [body] (type, flags, sizes, fields) exactly as given, behind a valid CRC. */
    private fun sealed(body: ByteArray): ByteArray {
        val sized = vint(body.size.toLong()) + body
        return le32(RarBuilder.crc(sized)) + sized
    }

    /** File header fields: no flags, Unix host; [nameSize] may disagree with [name]. */
    private fun fileFields(name: String, size: Long, compression: Long = 0, nameSize: Long = name.length.toLong()) =
        vint(0) + vint(size) + vint(0) + vint(compression) + vint(1) + vint(nameSize) + name.toByteArray()

    private class RecordingBudget : RarMemoryBudget {
        var balance = 0L
        val acquired = mutableListOf<Long>()

        override fun acquire(bytes: Long) {
            acquired += bytes
            balance += bytes
        }

        override fun release(bytes: Long) {
            balance -= bytes
        }
    }

    @Test
    fun `a header at maxHeaderSize is read, one byte over is refused`() {
        val bytes = RarBuilder().main(extra = record(0x7F, ByteArray(100))).end().build()
        // The main header's size field starts after the signature and its CRC.
        val size = HeaderCursor(bytes, RarSignature.RAR5_SIZE + 4, bytes.size).vint().toInt()
        RarReader(ByteArrayInputStream(bytes), RarOptions(limits = RarLimits(maxHeaderSize = size))).use { assertEquals(null, it.nextEntry()) }
        val e = assertThrows<RarUnsupportedException> { RarReader(ByteArrayInputStream(bytes), RarOptions(limits = RarLimits(maxHeaderSize = size - 1))) }
        assertEquals(RarUnsupportedReason.LimitExceeded("maxHeaderSize", size.toLong(), size - 1L), e.reason)
    }

    @Test
    fun `a size field declaring a header over maxHeaderSize is refused before the header is read`() {
        // CRC, then a 3-byte size field declaring 2 MiB - 1, then nothing.
        val bytes = RarBuilder.SIGNATURE + le32(0) + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F)
        val limits = RarLimits(maxHeaderSize = 4096)
        assertAllocatesBelow(1L shl 20) {
            val e = assertThrows<RarUnsupportedException> { RarReader(ByteArrayInputStream(bytes), RarOptions(limits = limits)) }
            assertEquals(RarUnsupportedReason.LimitExceeded("maxHeaderSize", RarLimits.MAX_HEADER_SIZE.toLong(), 4096), e.reason)
        }
        // Within the default limit, the missing header is a premature end.
        assertThrows<RarCorruptException> { RarReader(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `a size field longer than 3 bytes is corrupt`() {
        val bytes = RarBuilder.SIGNATURE + le32(0) + byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01) + ByteArray(64)
        val e = assertThrows<RarCorruptException> { RarReader(ByteArrayInputStream(bytes)) }
        assertEquals("Header size field too long", e.message)
    }

    @Test
    fun `a name longer than its header is corrupt, whatever maxNameBytes allows`() {
        val bytes = RarBuilder().main()
            .block(type = HeaderType.FILE, fields = fileFields("a.txt", 3, nameSize = huge), data = "abc".toByteArray())
            .end().build()
        for (limit in listOf(RarLimits().maxNameBytes, Int.MAX_VALUE)) {
            assertAllocatesBelow(1L shl 20) {
                RarReader(ByteArrayInputStream(bytes), RarOptions(limits = RarLimits(maxNameBytes = limit))).use { reader ->
                    assertEquals("File name exceeds header size", assertThrows<RarCorruptException> { reader.nextEntry() }.message)
                }
            }
        }
    }

    @Test
    fun `an extra area larger than its header is corrupt`() {
        val file = sealed(vint(HeaderType.FILE) + vint(HeaderFlags.EXTRA_DATA or HeaderFlags.DATA) + vint(huge) + vint(3) + fileFields("a.txt", 3))
        val bytes = RarBuilder().main().build() + file + "abc".toByteArray() + RarBuilder(withSignature = false).end().build()
        assertAllocatesBelow(1L shl 20) {
            RarReader(ByteArrayInputStream(bytes)).use { reader ->
                assertEquals("Extra area exceeds header size", assertThrows<RarCorruptException> { reader.nextEntry() }.message)
            }
        }
        val main = RarBuilder.SIGNATURE + sealed(vint(HeaderType.MAIN) + vint(HeaderFlags.EXTRA_DATA) + vint(huge) + vint(0))
        assertEquals("Extra area exceeds header size", assertThrows<RarCorruptException> { RarReader(ByteArrayInputStream(main)) }.message)
    }

    @Test
    fun `an entry of unknown size in the largest window reserves only maxWindowBytes`() {
        // Five main and five fraction bits top out at 63 << 43 (CompressionInfoTest), so the window
        // size cannot overflow 64 bits; it can only exceed the limit.
        val window = compression(version = 1, method = 3, main = 31, fraction = 31, compat = true)
        val bytes = RarBuilder().main().file("a.bin", "data".toByteArray(), compression = window, size = null, crc = null).end().build()
        val limits = RarLimits(maxWindowBytes = 1L shl 20)
        assertAllocatesBelow(4L shl 20) {
            val budget = RecordingBudget()
            RarReader(ByteArrayInputStream(bytes), RarOptions(limits = limits, memoryBudget = budget)).use { reader ->
                reader.nextEntry()!!
                val stream = reader.entryStream()
                assertEquals(listOf(1L shl 20), budget.acquired)
                // "data" is no compressed block: its first byte declares a 5-byte block size.
                assertThrows<RarCorruptException> { stream.readAllBytes() }
                assertEquals(0L, budget.balance)
            }
        }
    }

    @Test
    fun `packed data beyond the end of the volume is a premature end`() {
        val stored = sealed(vint(HeaderType.FILE) + vint(HeaderFlags.DATA) + vint(huge) + fileFields("stored.bin", 10))
        val storedArchive = RarBuilder().main().build() + stored + ByteArray(10)
        // A compressed block whose header promises more than the 10 bytes that follow.
        val block = LzBlock().literals(ByteArray(1000)).build().copyOf(10)
        val lz = sealed(vint(HeaderType.FILE) + vint(HeaderFlags.DATA) + vint(huge) + fileFields("lz.bin", 1000, compression(method = 3)))
        val lzArchive = RarBuilder().main().build() + lz + block
        for (mode in ReadMode.entries) {
            assertAllocatesBelow(2L shl 20) {
                assertThrows<RarCorruptException>("lz $mode") { walk(lzArchive, mode) }
                val e = assertThrows<RarCorruptException>("stored $mode") { walk(storedArchive, mode) }
                // Read, the stored entry's sizes already disagree.
                if (mode == ReadMode.READ) assertEquals("Stored entry 0 has packed size $huge but size 10", e.message)
            }
        }
    }

    @Test
    fun `maxKdfLog2 cannot exceed 24 and higher iteration counts are refused before asking`() {
        assertThrows<IllegalArgumentException> { RarLimits(maxKdfLog2 = 25) }
        assertThrows<IllegalArgumentException> { RarLimits(maxKdfLog2 = 100) }
        for (count in listOf(25, 63, 255)) {
            val expected = RarUnsupportedReason.LimitExceeded("maxKdfLog2", count.toLong(), 24)
            val provider = RecordingProvider("password")
            val derivation = CountingDerivation()
            val crypt = record(1, vint(0) + vint(0x01) + byteArrayOf(count.toByte()) + ByteArray(16) + ByteArray(16) + ByteArray(12))
            val entry = RarBuilder().main().file("e.bin", ByteArray(16), size = 16, extra = crypt).end().build()
            cryptReader(ByteArrayInputStream(entry), RarOptions(passwordProvider = provider), derivation).use { reader ->
                reader.nextEntry()!!
                assertEquals(expected, assertThrows<RarUnsupportedException>("entry, count $count") { reader.entryStream() }.reason)
            }
            val headers = RarBuilder().block(type = HeaderType.CRYPT, fields = vint(0) + vint(0) + byteArrayOf(count.toByte()) + ByteArray(16)).build()
            val e = assertThrows<RarUnsupportedException>("headers, count $count") {
                cryptReader(ByteArrayInputStream(headers), RarOptions(passwordProvider = provider), derivation)
            }
            assertEquals(expected, limitOf(e))
            assertTrue(provider.requests.isEmpty(), "count $count")
            assertEquals(0, derivation.calls, "count $count")
        }
    }

    // Compressed streams written by LzBlock. Window main 0 is 128 KiB, main 7 is 16 MiB.

    private val text = ByteArray(128) { ('A' + it % 26).code.toByte() }

    private class Decoded(val data: ByteArray, val filterCounts: List<Int>)

    private fun lz(block: LzBlock, size: Long?, expected: ByteArray? = null, main: Int = 0): ByteArray = RarBuilder().main()
        .file("lz.bin", block.build(), compression = compression(method = 3, main = main), size = size, crc = expected?.let { RarBuilder.crc(it) })
        .end().build()

    private fun decode(archive: ByteArray, limits: RarLimits = RarLimits()): Decoded =
        RarReader(ByteArrayInputStream(archive), RarOptions(limits = limits)).use { reader ->
            reader.nextEntry()!!
            Decoded(reader.entryStream().readAllBytes(), reader.filterCounts.toList())
        }

    private fun corruption(archive: ByteArray, limits: RarLimits = RarLimits()): String =
        assertThrows<RarCorruptException> { decode(archive, limits) }.message!!

    @Test
    fun `crafted literals decode`() {
        val hello = "hello".toByteArray()
        assertArrayEquals(hello, decode(lz(LzBlock().literals(hello), 5, hello)).data)
    }

    @Test
    fun `pending filters beyond maxPendingFilters are corrupt`() {
        val limits = RarLimits(maxPendingFilters = 3)
        fun filtered(count: Int) = LzBlock().apply { repeat(count) { filter(start = 4L * it, length = 4, type = Rar5Filters.E8) } }.literals(text.copyOf(16))
        // A 4-byte E8 block holds no complete instruction, so the filter leaves its bytes as they are.
        val three = decode(lz(filtered(3), 16, text.copyOf(16)), limits)
        assertArrayEquals(text.copyOf(16), three.data)
        assertEquals(3, three.filterCounts[Rar5Filters.E8])
        assertEquals("More than 3 pending filters", corruption(lz(filtered(4), 16), limits))
    }

    @Test
    fun `a filter longer than 4 MiB is corrupt even in a window that could hold it`() {
        fun block(length: Long) = LzBlock().filter(start = 0, length = length, type = Rar5Filters.E8).literals(text.copyOf(16))
        assertAllocatesBelow(2L shl 20) {
            assertEquals("Invalid filter (start 0, length 4194305)", corruption(lz(block(0x400001), 16, main = 7)))
            // At exactly 4 MiB the filter is accepted; the 16 bytes that follow cannot complete it.
            assertEquals("Compressed data ended after 0 of 16 bytes", corruption(lz(block(0x400000), 16, main = 7)))
        }
    }

    @Test
    fun `a filter longer than half the window is corrupt`() {
        fun block(length: Long) = LzBlock().filter(start = 0, length = length, type = Rar5Filters.E8).literals(text.copyOf(16))
        assertEquals("Invalid filter (start 0, length 65537)", corruption(lz(block(65537), 16)))
        assertEquals("Compressed data ended after 0 of 16 bytes", corruption(lz(block(65536), 16)))
    }

    @Test
    fun `filters covering more than maxPendingFilterBytes are corrupt`() {
        val limits = RarLimits(maxPendingFilterBytes = 100)
        val exact = decode(lz(LzBlock().filter(start = 0, length = 100, type = Rar5Filters.E8).literals(text), 128, text), limits)
        assertArrayEquals(text, exact.data)
        val over = LzBlock().filter(start = 0, length = 101, type = Rar5Filters.E8).literals(text)
        assertEquals("Pending filters cover more than 100 bytes", corruption(lz(over, 128), limits))
        val together = LzBlock().filter(start = 0, length = 60, type = Rar5Filters.E8).filter(start = 60, length = 60, type = Rar5Filters.E8).literals(text)
        assertEquals("Pending filters cover more than 100 bytes", corruption(lz(together, 128), limits))
    }

    @Test
    fun `a filter that starts past the entry's data never runs`() {
        val data = text.copyOf(16)
        for (size in listOf(16L, null)) {
            val decoded = decode(lz(LzBlock().filter(start = 1000, length = 8, type = Rar5Filters.E8).literals(data), size, data))
            assertArrayEquals(data, decoded.data, "size $size")
            assertEquals(listOf(0, 0, 0, 0), decoded.filterCounts, "size $size")
        }
    }

    @Test
    fun `filters reaching past the entry's data or overlapping another are corrupt`() {
        val past = LzBlock().filter(start = 8, length = 16, type = Rar5Filters.E8).literals(text.copyOf(16))
        assertEquals("Compressed data ended after 8 of 16 bytes", corruption(lz(past, 16)))
        val overlapping = LzBlock().filter(start = 0, length = 8, type = Rar5Filters.E8).filter(start = 4, length = 8, type = Rar5Filters.E8)
            .literals(text.copyOf(16))
        assertEquals("Invalid filter (start 4, length 8)", corruption(lz(overlapping, 16)))
    }

    @Test
    fun `an unknown filter type fails its entry and blocks the rest of its solid stream`() {
        val data = text.copyOf(16)
        val hello = "hello".toByteArray()
        val bytes = RarBuilder().main(flags = 0x04)
            .file("bad.bin", LzBlock().filter(start = 0, length = 8, type = 4).literals(data).build(), compression = compression(method = 3), size = 16, crc = RarBuilder.crc(data))
            .file("solid.bin", LzBlock().literals(hello).build(), compression = compression(method = 3, solid = true), size = 5, crc = RarBuilder.crc(hello))
            .file("fresh.bin", LzBlock().literals(hello).build(), compression = compression(method = 3), size = 5, crc = RarBuilder.crc(hello))
            .end().build()

        // Read, or skipped and decoded.
        for (readBad in listOf(true, false)) {
            val budget = RecordingBudget()
            RarReader(ByteArrayInputStream(bytes), RarOptions(memoryBudget = budget)).use { reader ->
                assertEquals("bad.bin", reader.nextEntry()!!.name)
                if (readBad) {
                    val own = assertThrows<RarUnsupportedException> { reader.entryStream().readAllBytes() }
                    assertEquals(RarUnsupportedReason.UnknownFilter(4), own.reason)
                    assertEquals(0L, budget.balance)
                }
                assertEquals("solid.bin", reader.nextEntry()!!.name)
                assertEquals(0L, budget.balance)
                val blocked = assertThrows<RarUnsupportedException>("read $readBad") { reader.entryStream() }
                assertEquals(RarUnsupportedReason.UnknownFilter(4), blocked.reason)
                assertEquals("Entry 0 (bad.bin) uses unsupported filter type 4", blocked.message)
                assertEquals("fresh.bin", reader.nextEntry()!!.name)
                assertArrayEquals(hello, reader.entryStream().readAllBytes())
                assertEquals(null, reader.nextEntry())
            }
            assertEquals(0L, budget.balance)
        }
        // Listing never parses filters.
        RarReader(ByteArrayInputStream(bytes), RarOptions(listOnly = true)).use { reader ->
            assertEquals(listOf("bad.bin", "solid.bin", "fresh.bin"), Fixtures.listAll(reader).map { it.name })
        }
    }

    @Test
    fun `over-subscribed Huffman tables are corrupt`() {
        assertEquals("Invalid Huffman bit length table", corruption(lz(LzBlock(bitLengths = IntArray(DecodeTable.HUFF_BC) { 1 }), 4)))
        val tables = listOf(
            0 until DecodeTable.HUFF_NC to "Invalid literal table",
            DecodeTable.HUFF_NC until DecodeTable.HUFF_NC + DecodeTable.HUFF_DC to "Invalid distance table",
            DecodeTable.HUFF_NC + DecodeTable.HUFF_DC until DecodeTable.HUFF_TABLE_SIZE - DecodeTable.HUFF_RC to "Invalid low distance table",
            DecodeTable.HUFF_TABLE_SIZE - DecodeTable.HUFF_RC until DecodeTable.HUFF_TABLE_SIZE to "Invalid repeat length table",
        )
        for ((range, message) in tables) {
            val lengths = LzBlock.DEFAULT_LENGTHS.copyOf().also { lengths -> range.forEach { lengths[it] = 1 } }
            assertEquals(message, corruption(lz(LzBlock(lengths = lengths), 4)))
        }
    }

    @Test
    fun `under-subscribed Huffman tables decode their codes`() {
        // Only 'a' has a code (1 bit, "0"); every other table is empty. Only over-subscription is
        // an error: a table with a single symbol cannot be complete.
        val lengths = IntArray(DecodeTable.HUFF_TABLE_SIZE).also { it['a'.code] = 1 }
        val a = "aaaa".toByteArray()
        assertArrayEquals(a, decode(lz(LzBlock(lengths = lengths).literals(a), 4, a)).data)
        // A code no symbol owns still decodes to some symbol, or fails as corrupt.
        val unassigned = LzBlock(lengths = lengths).literal('a'.code).bits(0x8000, 16).literal('a'.code)
        try {
            decode(lz(unassigned, size = null))
        } catch (e: RarCorruptException) {
        }
    }
}

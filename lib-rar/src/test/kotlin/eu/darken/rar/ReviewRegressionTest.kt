package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ReviewRegressionTest {
    /** Volume [index] of [count], holding one part of the split entry [name] (copied from VolumeSetTest). */
    private fun partVolume(
        index: Int,
        count: Int,
        name: String,
        part: ByteArray,
        size: Long?,
        crc: Int?,
        extra: ByteArray = ByteArray(0),
    ): ByteArray {
        val last = index == count - 1
        var flags = 0L
        if (index > 0) flags = flags or HeaderFlags.SPLIT_BEFORE
        if (!last) flags = flags or HeaderFlags.SPLIT_AFTER
        return RarBuilder().main(flags = 0x01, volumeNumber = if (index == 0) null else index.toLong())
            .file(name, part, size = size, crc = crc, extra = extra, blockFlags = flags)
            .end(moreVolumes = !last).build()
    }

    /** Serves [bytes] one byte per read and throws [error] once, from the read that starts at offset [at]. */
    private class ThrowOnceInputStream(private val bytes: ByteArray, private val at: Int, private val error: Throwable) : InputStream() {
        private var pos = 0
        private var thrown = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= bytes.size) return -1
            if (!thrown && pos == at) {
                thrown = true
                throw error
            }
            b[off] = bytes[pos++]
            return 1
        }

        override fun skip(n: Long): Long {
            val k = minOf(n, (bytes.size - pos).toLong()).coerceAtLeast(0L)
            pos += k.toInt()
            return k
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `F2 decode-on-skip of a solid split entry ignores a wrong part checksum`() {
        // compressed.rar's LZ stream for test.bin, split over two volumes of a solid set; the first
        // part stores a wrong CRC32 of its packed bytes. A stored note.txt follows in volume 1.
        val source = Fixtures.bytes("libarchive/test_read_format_rar5_compressed.rar")
        val raw = RarBlocks.file(source, "test.bin")
        val header = FileHeader.parse(RarBlocks.header(source, raw), service = false, limits = RarLimits())
        val packed = source.copyOfRange(raw.headerEnd, raw.dataEnd)
        val p1 = packed.copyOfRange(0, 150)
        val p2 = packed.copyOfRange(150, packed.size)
        val compression = header.compression.raw
        val size = checkNotNull(header.size)
        val note = "A stored note after the split entry.".toByteArray()
        val set = listOf(
            RarBuilder().main(flags = 0x05)
                .file("test.bin", p1, compression = compression, size = size, crc = RarBuilder.crc(p1) + 1, blockFlags = HeaderFlags.SPLIT_AFTER)
                .end(moreVolumes = true).build(),
            RarBuilder().main(flags = 0x05, volumeNumber = 1)
                .file("test.bin", p2, compression = compression, size = size, crc = checkNotNull(header.crc32), blockFlags = HeaderFlags.SPLIT_BEFORE)
                .file("note.txt", note)
                .end().build(),
        )

        // Control: reading the entry verifies the part and rejects it.
        val read = TestVolumes(set)
        RarReader(read.first(), RarOptions(volumeProvider = read)).use { reader ->
            reader.nextEntry()!!
            val e = assertThrows<RarCorruptException> { reader.entryStream().readAllBytes() }
            assertTrue(e.message!!.contains("part"), e.message)
        }

        // Skipped, the entry is decoded for the solid stream but its data is not verified.
        val skipped = TestVolumes(set)
        val names = RarReader(skipped.first(), RarOptions(volumeProvider = skipped)).use { reader ->
            assertDoesNotThrow { Fixtures.listAll(reader).map { it.name } }
        }
        assertEquals(listOf("test.bin", "note.txt"), names)
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `F3 a caller stream throwing RarUnsupportedException poisons the reader`() {
        val bytes = Fixtures.bytes("libarchive/test_read_format_rar5_compressed.rar")
        // Ten bytes into test.bin's packed data, while the decoder pulls it.
        val at = RarBlocks.file(bytes, "test.bin").headerEnd + 10
        val boom = RarUnsupportedException(RarUnsupportedReason.UnknownFilter(4))
        RarReader(ThrowOnceInputStream(bytes, at, boom)).use { reader ->
            assertEquals("test.bin", reader.nextEntry()!!.name)
            val thrown = assertThrows<RarUnsupportedException> { reader.entryStream().readAllBytes() }
            assertSame(boom, thrown)
            val poisoned = assertThrows<IOException>("the stream's exception did not poison the reader") { reader.nextEntry() }
            assertSame(boom, poisoned.cause)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `F4 RarLimits rejects a KDF log2 limit above 24`() {
        RarLimits(maxKdfLog2 = 24)
        assertThrows<IllegalArgumentException> { RarLimits(maxKdfLog2 = 25) }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `F5 a solid blocker for an unsupported entry names the blocking entry`() {
        val hello = "hello".toByteArray()
        val cases = listOf(
            RarBuilder.compression(method = 6) to RarUnsupportedReason.UnknownMethod(6),
            RarBuilder.compression(version = 2, method = 3, compat = true) to RarUnsupportedReason.AlgorithmVersion(2),
        )
        for ((compression, reason) in cases) {
            val archive = RarBuilder().main(flags = 0x04)
                .file("bad.bin", ByteArray(16), compression = compression)
                .file("solid.bin", LzBlock().literals(hello).build(), compression = RarBuilder.compression(method = 3, solid = true), size = 5, crc = RarBuilder.crc(hello))
                .end().build()
            RarReader(ByteArrayInputStream(archive)).use { reader ->
                assertEquals("bad.bin", reader.nextEntry()!!.name)
                // Skipping bad.bin blocks the solid stream.
                assertEquals("solid.bin", reader.nextEntry()!!.name)
                val e = assertThrows<RarUnsupportedException> { reader.entryStream() }
                assertEquals(reason, e.reason)
                val message = e.message.orEmpty()
                assertTrue(message.contains("0 (bad.bin)"), "$reason: the blocker's message does not name entry 0 (bad.bin): \"$message\"")
            }
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `F7 compressed output past the declared size is corrupt at an output-step boundary`() {
        // 65,537 literals; the header declares 65,536 bytes and the CRC32 of exactly those.
        val data = ByteArray(65537) { (it * 7 + it / 251).toByte() }
        val declared = data.copyOf(65536)
        val archive = RarBuilder().main()
            .file("lz.bin", LzBlock().literals(data).build(), compression = RarBuilder.compression(method = 3), size = 65536, crc = RarBuilder.crc(declared))
            .end().build()
        RarReader(ByteArrayInputStream(archive)).use { reader ->
            assertEquals(65536L, reader.nextEntry()!!.size)
            assertThrows<RarCorruptException> { reader.entryStream().readAllBytes() }
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `F8 a split encrypted entry whose joined ciphertext is not whole AES blocks is corrupt`() {
        val crypt = TestCrypt("secret")
        // Exactly one 16 KiB crypto buffer of ciphertext, then 3 stray packed bytes.
        val data = ByteArray(16384) { (it * 13).toByte() }
        val packed = crypt.encrypt(data) + byteArrayOf(1, 2, 3)
        assertEquals(16387, packed.size)
        val first = packed.copyOfRange(0, 10000)
        val second = packed.copyOfRange(10000, packed.size)
        val set = listOf(
            partVolume(0, 2, "e.bin", first, 16384, RarBuilder.crc(first), crypt.record()),
            partVolume(1, 2, "e.bin", second, 16384, RarBuilder.crc(data), crypt.record()),
        )
        val volumes = TestVolumes(set)
        RarReader(volumes.first(), RarOptions(passwordProvider = { "secret".toCharArray() }, volumeProvider = volumes)).use { reader ->
            assertEquals("e.bin", reader.nextEntry()!!.name)
            assertThrows<RarCorruptException> { reader.entryStream().readAllBytes() }
        }
    }
}

package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import kotlin.random.Random

class WindowTest {
    @Test
    fun `chunked ring matches a flat history`() {
        // Window sizes that are not powers of two, chunks that do not divide them, and a chunk larger than the window.
        for ((size, chunkSize) in listOf(1000L to 64, 1024L to 16, 4097L to 256, 300L to 512, 131072L to 4096)) {
            val window = Rar5Window(size, chunkSize, maxStorage = size, maxWindowBytes = Long.MAX_VALUE)
            val chunk = Rar5Window.chunkFor(size, chunkSize)
            val history = ByteArray(200_000)
            var pos = 0
            val random = Random(size)
            while (pos < history.size - 5000) {
                if (pos == 0 || random.nextInt(4) == 0) {
                    val b = random.nextInt().toByte()
                    window.put(b)
                    history[pos++] = b
                } else {
                    val distance = random.nextLong(1, minOf(pos.toLong(), size) + 1)
                    val length = random.nextInt(2, 4101)
                    window.copy(distance, length)
                    for (i in 0 until length) history[pos + i] = history[pos + i - distance.toInt()]
                    pos += length
                }
                assertEquals(pos.toLong(), window.position)
                assertEquals(Rar5Window.storageFor(minOf(pos.toLong(), size), size, chunk), window.allocated, "size $size at $pos")
                val visible = minOf(pos.toLong(), size).toInt()
                val tail = ByteArray(visible)
                window.copyOut(pos.toLong() - visible, tail, 0, visible)
                assertArrayEquals(history.copyOfRange(pos - visible, pos), tail, "size $size chunk $chunkSize at $pos")
            }
        }
    }

    @Test
    fun `storage stops at the reservation and the dictionary limit`() {
        val limited = Rar5Window(1000, 64, maxStorage = 1000, maxWindowBytes = 500)
        repeat(500) { limited.put(1) }
        val e = assertThrows<RarUnsupportedException> { limited.put(1) }
        assertEquals(RarUnsupportedReason.DictionaryTooLarge(1000, 500), e.reason)

        val reserved = Rar5Window(1000, 64, maxStorage = 128, maxWindowBytes = Long.MAX_VALUE)
        repeat(128) { reserved.put(1) }
        assertThrows<RarCorruptException> { reserved.copy(1, 2) }
    }

    private fun contents(path: String, chunkSize: Int?): List<Pair<String, String?>> {
        val stream = Fixtures.stream(path)
        val reader = if (chunkSize == null) RarReader(stream) else RarReader(stream, RarOptions(), chunkSize)
        return reader.use { r ->
            generateSequence { r.nextEntry() }.map { entry ->
                val data = if (entry.isDirectory || entry.redirection != null) null else r.entryStream().use { it.readAllBytes() }
                entry.name to data?.let { Fixtures.sha256(it) }
            }.toList()
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "rar723/dict-128k.rar", "rar723/dict-1m.rar", "rar723/solid.rar", "rar723/filter-delta.rar", "rar723/filter-e8e9.rar",
            "rar723/m5.rar", "libarchive/test_read_format_rar5_arm.rar", "libarchive/test_read_format_rar5_multiple_files_solid.rar",
        ],
    )
    fun `tiny chunks decode identically`(path: String) {
        val expected = contents(path, chunkSize = null)
        assertTrue(expected.any { it.second != null })
        for (chunkSize in listOf(16, 64)) assertEquals(expected, contents(path, chunkSize), "$path chunk $chunkSize")
    }

    @Test
    fun `a non-power-of-two window wraps correctly`() {
        // dict-128k.rar's stream only reaches back 128 KiB; declared as version 1 with a 33/32
        // fraction, the window is 135168 bytes and the 2.2 MB of output wrap it 16 times.
        val source = Fixtures.bytes("rar723/dict-128k.rar")
        val expected = Fixtures.rar723.fixtures.getValue("dict-128k.rar").entries.single()
        val compression = RarBuilder.compression(version = 1, method = 3, main = 0, fraction = 1, compat = true)
        assertEquals(135168L, CompressionInfo(compression).windowBytes)
        val archive = RarBuilder().main()
            .file("dictdata.bin", RarBlocks.data(source, "dictdata.bin"), compression = compression, size = 2_200_000, crc = 0x852EF27E.toInt())
            .end().build()
        for (chunkSize in listOf(Rar5Window.DEFAULT_CHUNK_SIZE, 4096, 16)) {
            RarReader(ByteArrayInputStream(archive), RarOptions(), chunkSize).use { reader ->
                reader.nextEntry()!!
                assertEquals(expected.sha256, Fixtures.sha256(reader.entryStream().readAllBytes()), "chunk $chunkSize")
                assertEquals(135168L, reader.windowStorageBytes)
            }
        }
    }

    @Test
    fun `dictionary limit at a known-size entry`() {
        // dict-128k.rar needs min(128 KiB window, 2.2 MB) = 131072 bytes.
        val fits = RarOptions(limits = RarLimits(maxWindowBytes = 131072))
        RarReader(Fixtures.stream("rar723/dict-128k.rar"), fits).use { reader ->
            reader.nextEntry()!!
            assertEquals(2_200_000, reader.entryStream().readAllBytes().size)
        }
        val tooSmall = RarOptions(limits = RarLimits(maxWindowBytes = 131071))
        RarReader(Fixtures.stream("rar723/dict-128k.rar"), tooSmall).use { reader ->
            reader.nextEntry()!!
            val e = assertThrows<RarUnsupportedException> { reader.entryStream() }
            assertEquals(RarUnsupportedReason.DictionaryTooLarge(131072, 131071), e.reason)
            assertNull(reader.nextEntry())
        }
        // A small entry in a large window only needs its own size: lorem.txt is 12000 bytes.
        RarReader(Fixtures.stream("rar723/m3.rar"), RarOptions(limits = RarLimits(maxWindowBytes = 12000))).use { reader ->
            reader.nextEntry()!!
            assertEquals("docs/lorem.txt", reader.nextEntry()!!.name)
            assertEquals(12000, reader.entryStream().readAllBytes().size)
            assertEquals("docs/numbers.txt", reader.nextEntry()!!.name)
            assertEquals(8893, reader.entryStream().readAllBytes().size)
        }
        RarReader(Fixtures.stream("rar723/m3.rar"), RarOptions(limits = RarLimits(maxWindowBytes = 11999))).use { reader ->
            reader.nextEntry()!!
            reader.nextEntry()!!
            val e = assertThrows<RarUnsupportedException> { reader.entryStream() }
            assertEquals(RarUnsupportedReason.DictionaryTooLarge(12000, 11999), e.reason)
            assertEquals("docs/numbers.txt", reader.nextEntry()!!.name)
            assertEquals(8893, reader.entryStream().readAllBytes().size)
        }
    }

    @Test
    fun `dictionary limit while a solid stream grows`() {
        // solid.rar's stream is 21070 bytes in a 1 MiB window, so only its growth can hit the limit.
        val fixture = Fixtures.rar723.fixtures.getValue("solid.rar")
        val files = fixture.entries.filter { it.type == "file" }
        assertEquals(21070L, files.sumOf { it.size!! })

        RarReader(Fixtures.stream("rar723/solid.rar"), RarOptions(limits = RarLimits(maxWindowBytes = 21070))).use { reader ->
            for (expected in files) {
                reader.nextEntry()!!
                assertEquals(expected.sha256, Fixtures.sha256(reader.entryStream().readAllBytes()), expected.name)
            }
        }

        RarReader(Fixtures.stream("rar723/solid.rar"), RarOptions(limits = RarLimits(maxWindowBytes = 21069))).use { reader ->
            for (expected in files.dropLast(1)) {
                reader.nextEntry()!!
                assertEquals(expected.sha256, Fixtures.sha256(reader.entryStream().readAllBytes()), expected.name)
            }
            assertEquals(files.last().name, reader.nextEntry()!!.name)
            val stream = reader.entryStream()
            val e = assertThrows<RarUnsupportedException> { stream.readAllBytes() }
            assertEquals(RarUnsupportedReason.DictionaryTooLarge(1L shl 20, 21069), e.reason)
            assertEquals(0L, reader.reservedBytes)
            // The reader stays usable; the directories that follow list normally.
            assertEquals(4, generateSequence { reader.nextEntry() }.count())
        }

        // Skipping instead of reading fails the same stream without throwing; a later solid entry reports it.
        RarReader(Fixtures.stream("rar723/solid.rar"), RarOptions(limits = RarLimits(maxWindowBytes = 12000))).use { reader ->
            repeat(4) { reader.nextEntry()!! }
            assertEquals("docs/numbers.txt", reader.nextEntry()!!.name)
            val e = assertThrows<RarUnsupportedException> { reader.entryStream() }
            assertEquals(RarUnsupportedReason.DictionaryTooLarge(1L shl 20, 12000), e.reason)
        }
    }
}

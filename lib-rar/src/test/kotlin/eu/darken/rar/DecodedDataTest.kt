package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.CRC32

class DecodedDataTest {
    companion object {
        private fun readable(e: ManifestEntry) = e.type == "file" && !e.encrypted && !e.splitAcrossVolumes && e.sha256 != null

        /** Single-volume RAR 7.23 fixtures with readable entries; dict-5g-v1.rar has an unsupported algorithm version. */
        @JvmStatic
        fun rar723(): List<String> = Fixtures.rar723.fixtures.values
            .filter { f -> !f.isVolumeSet && !f.headerEncrypted && f.name != "dict-5g-v1.rar" && f.entries.any { readable(it) } }
            .map { it.name }

        /** Fixtures whose filters the instrumented bsdtar recorded, and which decode without a password. */
        @JvmStatic
        fun scannedFilters(): List<String> = rar723().filter { Fixtures.rar723.fixtures.getValue(it).filterTypes != null }

        /** libarchive fixtures that libarchive's own tests extract successfully. */
        val LIBARCHIVE_EXTRACTED = listOf(
            "compressed", "multiple_files", "multiple_files_solid", "solid", "blake2", "arm", "win32", "extra_field_version",
        ).map { "test_read_format_rar5_$it.rar" }

        @JvmStatic
        fun libarchiveExtracted(): List<String> = LIBARCHIVE_EXTRACTED

        /** libarchive's verify_data(): little-endian ints k*k - 3k + 1 + magic for k = 1.., clamped at 0. */
        fun verifyData(data: ByteArray, magic: Int): Boolean = (0 until data.size / 4).all { i ->
            val k = i + 1
            val value = maxOf(k * k - 3 * k + 1 + magic, 0)
            val actual = (data[i * 4].toInt() and 0xFF) or
                ((data[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((data[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((data[i * 4 + 3].toInt() and 0xFF) shl 24)
            actual == value
        }

        fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value
    }

    private fun readAll(path: String, options: RarOptions = RarOptions(), chunkSize: Int? = null): Pair<RarReader, Map<Int, ByteArray>> {
        val reader = if (chunkSize == null) RarReader(Fixtures.stream(path), options) else RarReader(Fixtures.stream(path), options, chunkSize)
        val data = mutableMapOf<Int, ByteArray>()
        reader.use {
            while (true) {
                val entry = it.nextEntry() ?: break
                if (entry.isDirectory || entry.redirection != null || entry.isEncrypted) continue
                data[entry.ordinal] = it.entryStream().use { s -> s.readAllBytes() }
            }
        }
        return reader to data
    }

    @ParameterizedTest
    @MethodSource("rar723")
    fun `every unencrypted entry matches its source`(name: String) {
        val fixture = Fixtures.rar723.fixtures.getValue(name)
        RarReader(Fixtures.stream(Fixtures.rar723.path(fixture.files.single()))).use { reader ->
            for (expected in fixture.entries) {
                val entry = reader.nextEntry() ?: fail("$name ended before ${expected.name}")
                assertEquals(expected.name, entry.name)
                if (!readable(expected)) continue
                val data = reader.entryStream().use { it.readAllBytes() }
                assertEquals(expected.size, data.size.toLong(), "$name ${entry.name}")
                assertEquals(expected.sha256, Fixtures.sha256(data), "$name ${entry.name}")
            }
            assertNull(reader.nextEntry())
        }
    }

    @Test
    fun `the content test covers every compressed fixture`() {
        val compressed = rar723().filter { name -> Fixtures.rar723.fixtures.getValue(name).entries.any { readable(it) && !it.stored } }
        val expected = listOf(
            "m1.rar", "m3.rar", "m5.rar", "filter-e8.rar", "filter-e8e9.rar", "filter-delta.rar", "solid.rar", "dict-128k.rar",
            "dict-1m.rar", "dict-4m.rar", "dict-default.rar", "blake2.rar", "comment.rar", "recovery.rar", "quickopen.rar", "locked.rar",
        )
        assertTrue(compressed.containsAll(expected), compressed.toString())
    }

    @ParameterizedTest
    @MethodSource("libarchiveExtracted")
    fun `libarchive fixtures decode with their checksums`(name: String) {
        val fixture = Fixtures.libarchive.getValue(name)
        var compressed = 0
        RarReader(Fixtures.stream("libarchive/$name")).use { reader ->
            for (expected in fixture.entries) {
                val entry = reader.nextEntry() ?: fail("$name ended before ${expected.name}")
                assertEquals(expected.baseName, entry.name)
                if (expected.ltType != "File") continue
                val data = reader.entryStream().use { it.readAllBytes() }
                val where = "$name ${expected.name}"
                assertEquals(expected.size, data.size.toLong(), where)
                expected.sha256?.let { assertEquals(it, Fixtures.sha256(data), where) }
                if (expected.checksum?.first() == "CRC32") assertEquals(expected.checksum!![1].toLong(16), crc32(data), where)
                if (expected.compression?.contains(" -m0") != true) compressed++
            }
            assertNull(reader.nextEntry())
        }
        assertTrue(compressed > 0, name)
    }

    @Test
    fun `libarchive's generated test data`() {
        // Expectations from libarchive's test_read_format_rar5.c.
        val (_, compressed) = readAll("libarchive/test_read_format_rar5_compressed.rar")
        assertEquals(1200, compressed.getValue(0).size)
        assertTrue(verifyData(compressed.getValue(0), 0))
        assertEquals(0x7CCA70CDL, crc32(compressed.getValue(0)))
        for (name in listOf("multiple_files", "multiple_files_solid")) {
            val (_, files) = readAll("libarchive/test_read_format_rar5_$name.rar")
            for (i in 0 until 4) {
                assertEquals(4096, files.getValue(i).size)
                assertTrue(verifyData(files.getValue(i), i + 1), "$name test${i + 1}.bin")
            }
        }
        val (_, arm) = readAll("libarchive/test_read_format_rar5_arm.rar")
        assertEquals(90808, arm.getValue(0).size)
        assertEquals(0x886F91EBL, crc32(arm.getValue(0)))
        val (_, versions) = readAll("libarchive/test_read_format_rar5_extra_field_version.rar")
        assertEquals(listOf(0xF24181B7L, 0xF24181B7L), versions.values.map { crc32(it) })
        val (_, blake2) = readAll("libarchive/test_read_format_rar5_blake2.rar")
        assertEquals(0x7E5EC49EL, crc32(blake2.getValue(0)))
    }

    @Test
    fun `each filter type runs`() {
        val expected = mapOf(
            "rar723/filter-delta.rar" to Rar5Filters.DELTA,
            "rar723/filter-e8.rar" to Rar5Filters.E8,
            "rar723/filter-e8e9.rar" to Rar5Filters.E8E9,
            "libarchive/test_read_format_rar5_arm.rar" to Rar5Filters.ARM,
        )
        for ((path, type) in expected) {
            val (reader, _) = readAll(path)
            assertTrue(reader.filterCounts[type] > 0, "$path ${reader.filterCounts.toList()}")
        }
    }

    @ParameterizedTest
    @MethodSource("scannedFilters")
    fun `filters match what libarchive parsed`(name: String) {
        val fixture = Fixtures.rar723.fixtures.getValue(name)
        val (reader, _) = readAll(Fixtures.rar723.path(fixture.files.single()))
        val ran = reader.filterCounts.withIndex().filter { it.value > 0 }.map { it.index }.toSet()
        assertEquals(fixture.filterTypes!!.toSet(), ran, name)
        assertEquals(fixture.filterCount, reader.filterCounts.sum(), name)
    }

    @Test
    fun `reading single entries of a solid archive decodes the skipped ones`() {
        val fixture = Fixtures.rar723.fixtures.getValue("solid.rar")
        for ((index, expected) in fixture.entries.withIndex()) {
            if (!readable(expected)) continue
            RarReader(Fixtures.stream("rar723/solid.rar")).use { reader ->
                repeat(index) { reader.nextEntry()!! }
                assertEquals(expected.name, reader.nextEntry()!!.name)
                assertEquals(expected.sha256, Fixtures.sha256(reader.entryStream().use { it.readAllBytes() }), expected.name)
            }
        }
        // Full reads, partial reads and untouched entries mixed; partial ones are finished by nextEntry().
        RarReader(Fixtures.stream("rar723/solid.rar")).use { reader ->
            for ((index, expected) in fixture.entries.withIndex()) {
                reader.nextEntry()!!
                if (!readable(expected)) continue
                when (index % 3) {
                    0 -> assertEquals(expected.sha256, Fixtures.sha256(reader.entryStream().readAllBytes()), expected.name)
                    1 -> reader.entryStream().readNBytes(5)
                    else -> Unit
                }
            }
        }
        // libarchive's test_read_format_rar5_solid_skip_all_but_last.
        RarReader(Fixtures.stream("libarchive/test_read_format_rar5_solid.rar")).use { reader ->
            repeat(6) { reader.nextEntry()!! }
            assertEquals("test6.bin", reader.nextEntry()!!.name)
            assertEquals(0x36A448FFL, crc32(reader.entryStream().readAllBytes()))
            assertNull(reader.nextEntry())
        }
    }

    @Test
    fun `listOnly lists the same entries`() {
        for (path in listOf("rar723/solid.rar", "rar723/m3.rar", "rar723/dict-1m.rar", "libarchive/test_read_format_rar5_solid.rar")) {
            val full = RarReader(Fixtures.stream(path)).use { reader ->
                generateSequence { reader.nextEntry() }.onEach { if (!it.isDirectory) reader.entryStream().readAllBytes() }.toList()
            }
            val (_, listed) = Fixtures.list(path, RarOptions(listOnly = true))
            assertEquals(full, listed, path)
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `output beyond the declared size is corrupt`() {
        // libarchive's regression sample is rejected earlier here: its main header declares a
        // 44-byte extra area inside a 10-byte header.
        assertThrows<RarCorruptException> {
            RarReader(Fixtures.stream("libarchive/test_read_format_rar5_unpacked_size_exceeds_declared.rar"))
        }
        // The same defect on a valid stream: compressed.rar's 1200 bytes declared as 1199.
        val packed = RarBlocks.data(Fixtures.bytes("libarchive/test_read_format_rar5_compressed.rar"), "test.bin")
        fun archive(solid: Boolean) = RarBuilder().main(flags = if (solid) 0x04L else 0L)
            .file("test.bin", packed, compression = RarBuilder.compression(method = 5), size = 1199, crc = 0x7CCA70CD)
            .end().build()
        for (solid in listOf(false, true)) {
            RarReader(ByteArrayInputStream(archive(solid))).use { reader ->
                assertEquals(1199L, reader.nextEntry()!!.size)
                assertThrows<RarCorruptException> { reader.entryStream().readAllBytes() }
                assertThrows<IOException> { reader.nextEntry() }
            }
        }
        // As in libarchive, skipping the entry in a solid archive decodes it and fails the same way.
        RarReader(ByteArrayInputStream(archive(solid = true))).use { reader ->
            reader.nextEntry()!!
            assertThrows<RarCorruptException> { reader.nextEntry() }
        }
        // Declared exactly, the same stream is fine.
        val exact = RarBuilder().main()
            .file("test.bin", packed, compression = RarBuilder.compression(method = 5), size = 1200, crc = 0x7CCA70CD)
            .end().build()
        RarReader(ByteArrayInputStream(exact)).use { reader ->
            reader.nextEntry()!!
            assertTrue(verifyData(reader.entryStream().readAllBytes(), 0))
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `an over-subscribed Huffman table is corrupt`() {
        RarReader(Fixtures.stream("libarchive/test_read_format_rar5_bad_tables.rar")).use { reader ->
            assertEquals("bad_tables.txt", reader.nextEntry()!!.name)
            assertThrows<RarCorruptException> { reader.entryStream().readAllBytes() }
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the ARM filter fuzz sample fails with a RarException`() {
        assertThrows<RarException> {
            RarReader(Fixtures.stream("libarchive/test_read_format_rar5_arm_filter_on_window_boundary.rar")).use { reader ->
                while (reader.nextEntry() != null) reader.entryStream().readAllBytes()
            }
        }
    }

    private fun damagedData(path: String, name: String): ByteArray {
        val bytes = Fixtures.bytes(path)
        val block = RarBlocks.file(bytes, name)
        val middle = (block.headerEnd + block.dataEnd) / 2
        bytes[middle] = (bytes[middle].toInt() xor 0x55).toByte()
        return bytes
    }

    @Test
    fun `damaged packed data is corrupt`() {
        // m3.rar carries CRC32s, blake2.rar only BLAKE2sp hashes.
        for (path in listOf("rar723/m3.rar", "rar723/blake2.rar")) {
            RarReader(ByteArrayInputStream(damagedData(path, "docs/lorem.txt"))).use { reader ->
                reader.nextEntry()!!
                assertEquals("docs/lorem.txt", reader.nextEntry()!!.name)
                assertThrows<RarCorruptException>(path) { reader.entryStream().readAllBytes() }
                assertThrows<IOException>(path) { reader.nextEntry() }
            }
        }
    }

    /** [path] with [stored] replaced inside the header of entry [name], header CRC resealed. */
    private fun wrongChecksum(path: String, name: String, stored: ByteArray): ByteArray {
        val bytes = Fixtures.bytes(path)
        val block = RarBlocks.file(bytes, name)
        val at = RarBlocks.find(bytes, stored, block.start + 4, block.headerEnd)
        bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
        RarBlocks.reseal(bytes, block)
        return bytes
    }

    @Test
    fun `a wrong CRC32 fails on the read that delivers the last byte`() {
        val bytes = wrongChecksum("rar723/m3.rar", "docs/lorem.txt", RarBuilder.le32(0x65920132))
        RarReader(ByteArrayInputStream(bytes)).use { reader ->
            reader.nextEntry()!!
            reader.nextEntry()!!
            val stream = reader.entryStream()
            assertEquals(11999, stream.readNBytes(11999).size)
            val e = assertThrows<RarCorruptException> { stream.read() }
            assertTrue(e.message!!.contains("CRC32"), e.message)
        }
    }

    @Test
    fun `a wrong BLAKE2sp fails on the read that delivers the last byte`() {
        val digest = Fixtures.rar723.fixtures.getValue("blake2.rar").entries.single { it.name == "docs/lorem.txt" }.ltChecksum!![1]
        val stored = ByteArray(32) { digest.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val bytes = wrongChecksum("rar723/blake2.rar", "docs/lorem.txt", stored)
        RarReader(ByteArrayInputStream(bytes)).use { reader ->
            reader.nextEntry()!!
            reader.nextEntry()!!
            val stream = reader.entryStream()
            assertEquals(11999, stream.readNBytes(11999).size)
            val e = assertThrows<RarCorruptException> { stream.read() }
            assertTrue(e.message!!.contains("BLAKE2sp"), e.message)
        }
        // The stored entries' digests are verified as well.
        val hello = Fixtures.rar723.fixtures.getValue("blake2.rar").entries.single { it.name == "hello.txt" }.ltChecksum!![1]
        val helloBytes = wrongChecksum("rar723/blake2.rar", "hello.txt", ByteArray(32) { hello.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        RarReader(ByteArrayInputStream(helloBytes)).use { reader ->
            reader.nextEntry()!!
            assertThrows<RarCorruptException> { reader.entryStream().readAllBytes() }
        }
    }

    @Test
    fun `one byte per read decodes the same`() {
        for (path in listOf("rar723/solid.rar", "rar723/filter-e8e9.rar", "libarchive/test_read_format_rar5_multiple_files_solid.rar")) {
            val bytes = Fixtures.bytes(path)
            val normal = HostileRun.contents(ByteArrayInputStream(bytes))
            val slow = HostileRun.contents(OneByteInputStream(ByteArrayInputStream(bytes)))
            assertEquals(normal.map { it.first }, slow.map { it.first }, path)
            normal.zip(slow).forEach { (a, b) -> assertArrayEquals(a.second, b.second, "$path ${a.first}") }
        }
    }
}

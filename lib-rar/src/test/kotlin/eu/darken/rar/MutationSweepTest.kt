package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream

/**
 * Truncation at every offset and every single-bit flip of small archives, each walked in every
 * [ReadMode] under [SweepCheck.LIMITS]: a run ends in success or a RarException, a truncation
 * before the end header in a premature-end failure, and no run over-reserves, over-allocates or
 * leaves a stream open.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MutationSweepTest {
    /** The bytes a sweep mutates, where its end header ends, and how one mutated copy is walked. */
    private class Subject(val bytes: ByteArray, val end: Int, val run: (ByteArray, ReadMode) -> SweepCheck.Result)

    companion object {
        private const val STORED = "test_read_format_rar5_stored.rar"
        private const val COMPRESSED = "test_read_format_rar5_compressed.rar"
        private const val SOLID = "test_read_format_rar5_multiple_files_solid.rar"
        private const val ENCRYPTED = "test_read_format_rar5_encrypted.rar"
        private const val HEADERS_ENCRYPTED = "test_read_format_rar5_encrypted_filenames.rar"

        private fun endOf(bytes: ByteArray): Int = RarBlocks.parse(bytes).last { it.type == HeaderType.ENDARC }.dataEnd

        private fun libarchive(name: String, end: (ByteArray) -> Int = ::endOf): Subject {
            val bytes = Fixtures.bytes("libarchive/$name")
            val options = RarOptions(limits = SweepCheck.LIMITS, passwordProvider = libarchivePasswords(name))
            return Subject(bytes, end(bytes)) { mutated, mode -> SweepCheck.run(mutated, mode, options) }
        }

        private fun single(bytes: ByteArray) = Subject(bytes, endOf(bytes)) { mutated, mode -> SweepCheck.run(mutated, mode) }

        /** Volume 1 of [volumeSet], read through the provider after an untouched volume 0. */
        private fun secondVolume(): Subject {
            val set = volumeSet()
            return Subject(set[1], endOf(set[1])) { mutated, mode ->
                val volumes = TestVolumes { index -> if (index == 1) mutated else set.getOrNull(index) }
                SweepCheck.run(set[0], mode, RarOptions(limits = SweepCheck.LIMITS, volumeProvider = volumes), volumes)
            }
        }

        private val SUBJECTS: Map<String, Lazy<Subject>> = mapOf(
            "stored" to lazy { libarchive(STORED) },
            "compressed" to lazy { libarchive(COMPRESSED) },
            "solid" to lazy { libarchive(SOLID) },
            "delta filter" to lazy { single(deltaFiltered()) },
            "encrypted data" to lazy { libarchive(ENCRYPTED) },
            // Encrypted headers cannot be walked; the archive ends with its encrypted end header.
            "encrypted headers" to lazy { libarchive(HEADERS_ENCRYPTED) { it.size } },
            "second volume" to lazy { secondVolume() },
        )

        @JvmStatic
        fun runs(): List<Arguments> = SUBJECTS.keys.flatMap { name -> ReadMode.entries.map { Arguments.of(name, it) } }

        /** m3.rar's compressed entry whose stream runs the delta filter, alone in an archive. */
        private fun deltaFiltered(): ByteArray {
            val m3 = Fixtures.bytes("rar723/m3.rar")
            val blocks = RarBlocks.parse(m3)
            fun copy(block: RarBlocks.Raw) = m3.copyOfRange(block.start, block.dataEnd)
            val main = copy(blocks.first { it.type == HeaderType.MAIN })
            val end = copy(blocks.last { it.type == HeaderType.ENDARC })
            return checkNotNull(
                blocks.filter { it.type == HeaderType.FILE && it.dataEnd > it.headerEnd }
                    .map { RarBuilder.SIGNATURE + main + copy(it) + end }
                    .filter { runsFilter(it, Rar5Filters.DELTA) }
                    .minByOrNull { it.size },
            ) { "m3.rar has no delta-filtered entry" }
        }

        private fun runsFilter(archive: ByteArray, type: Int): Boolean = RarReader(ByteArrayInputStream(archive)).use { reader ->
            while (reader.nextEntry() != null) reader.entryStream().use { it.readAllBytes() }
            reader.filterCounts[type] > 0
        }

        /**
         * Three volumes: compressed.rar's LZ stream for test.bin split over volumes 0 and 1, then
         * note.txt, stored, split over volumes 1 and 2. A part followed by another stores the CRC32
         * of its packed bytes, the last part the file's.
         */
        fun volumeSet(): List<ByteArray> {
            val source = Fixtures.bytes("libarchive/$COMPRESSED")
            val raw = RarBlocks.file(source, "test.bin")
            val header = FileHeader.parse(RarBlocks.header(source, raw), service = false, limits = RarLimits())
            val packed = source.copyOfRange(raw.headerEnd, raw.dataEnd)
            val p1 = packed.copyOfRange(0, 150)
            val p2 = packed.copyOfRange(150, packed.size)
            val note = NOTE.toByteArray()
            val n1 = note.copyOfRange(0, 20)
            val n2 = note.copyOfRange(20, note.size)
            val compression = header.compression.raw
            val size = checkNotNull(header.size)
            val noteSize = note.size.toLong()
            return listOf(
                RarBuilder().main(flags = 0x01)
                    .file("test.bin", p1, compression = compression, size = size, crc = RarBuilder.crc(p1), blockFlags = HeaderFlags.SPLIT_AFTER)
                    .end(moreVolumes = true).build(),
                RarBuilder().main(flags = 0x01, volumeNumber = 1)
                    .file("test.bin", p2, compression = compression, size = size, crc = checkNotNull(header.crc32), blockFlags = HeaderFlags.SPLIT_BEFORE)
                    .file("note.txt", n1, size = noteSize, crc = RarBuilder.crc(n1), blockFlags = HeaderFlags.SPLIT_AFTER)
                    .end(moreVolumes = true).build(),
                RarBuilder().main(flags = 0x01, volumeNumber = 2)
                    .file("note.txt", n2, size = noteSize, crc = RarBuilder.crc(note), blockFlags = HeaderFlags.SPLIT_BEFORE)
                    .end().build(),
            )
        }

        const val NOTE = "A stored note that continues into the third volume."
    }

    private fun subject(name: String): Subject = SUBJECTS.getValue(name).value

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("runs")
    fun `the unmutated subjects read cleanly`(name: String, mode: ReadMode) {
        val subject = subject(name)
        val result = subject.run(subject.bytes, mode)
        assertEquals(null, result.outcome, "$name $mode")
        assertEquals(emptyList<String>(), result.violations, "$name $mode")
    }

    @Test
    fun `the crafted volume set and the encrypted subjects hold the expected data`() {
        val set = volumeSet()
        val volumes = TestVolumes(set)
        RarReader(volumes.first(), RarOptions(volumeProvider = volumes)).use { reader ->
            assertEquals("test.bin", reader.nextEntry()!!.name)
            assertTrue(DecodedDataTest.verifyData(reader.entryStream().readAllBytes(), 0))
            assertEquals("note.txt", reader.nextEntry()!!.name)
            assertArrayEquals(NOTE.toByteArray(), reader.entryStream().readAllBytes())
            assertEquals(null, reader.nextEntry())
        }
        assertEquals(listOf(0, 1, 2), volumes.opened)

        // Passwords per libarchive's test_read_format_rar_encryption.c.
        for (name in listOf(ENCRYPTED, HEADERS_ENCRYPTED)) {
            val contents = RarReader(Fixtures.stream("libarchive/$name"), RarOptions(passwordProvider = libarchivePasswords(name))).use { reader ->
                generateSequence { reader.nextEntry() }.map { String(reader.entryStream().readAllBytes()) }.toList()
            }
            assertEquals(listOf("a", "b", "c", "d").map { "This is from $it.txt" }, contents, name)
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("runs")
    fun `truncation at every offset`(name: String, mode: ReadMode) {
        val subject = subject(name)
        SweepCheck.assertAll("$name $mode", SweepCheck.truncations(subject.bytes)) { mutated ->
            SweepCheck.judge(subject.run(mutated, mode), truncatedTo = mutated.size, end = subject.end)
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("runs")
    fun `every single-bit flip`(name: String, mode: ReadMode) {
        val subject = subject(name)
        SweepCheck.assertAll("$name $mode", SweepCheck.bitFlips(subject.bytes)) { mutated ->
            SweepCheck.judge(subject.run(mutated, mode))
        }
    }
}

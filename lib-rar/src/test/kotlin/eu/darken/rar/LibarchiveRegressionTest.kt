package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * libarchive v3.8.10's RAR5 regression samples: every fixture in every [ReadMode], and the
 * expectations libarchive's tests (`test_read_format_rar5*.c`) state for the fuzzing samples and
 * for the archives they spell out byte by byte. This reader parses the first block in its
 * constructor and each header within its CRC-checked size, so it often fails earlier than libarchive.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LibarchiveRegressionTest {
    companion object {
        @JvmStatic
        fun runs(): List<Arguments> = Fixtures.libarchive.keys.sorted().flatMap { name ->
            ReadMode.entries.flatMap { mode -> listOf(true, false).map { passwords -> Arguments.of(name, mode, passwords) } }
        }

        private fun name(sample: String) = "test_read_format_rar5_$sample.rar"

        /**
         * Fuzzing samples whose first block is invalid: a header size of 0, a main header that ends
         * before its archive flags, or an extra area larger than its header. libarchive's tests
         * expect a failed first header or merely no crash, except for data_ready_pointer_leak, whose
         * missing flags libarchive reads from the bytes after the header. unpacked_size_exceeds_declared:
         * DecodedDataTest.
         */
        private val FIRST_BLOCK = listOf(
            "arm_filter_on_window_boundary", "bad_window_sz_in_mltarc_file", "block_size_is_too_small",
            "data_ready_pointer_leak", "decode_number_out_of_bounds_read", "different_solid_window_size",
            "different_window_size", "different_winsize_on_merge", "dirdata", "distance_overflow",
            "invalid_dict_reference", "leftshift1", "leftshift2", "nonempty_dir_stream", "readtables_overflow",
            "redir_overflow", "truncated_huff", "window_buf_and_size_desync",
        )

        /** Samples whose first file header libarchive rejects: an owner name, extra area or record beyond its container. */
        private val FIRST_ENTRY = listOf("owner_name_toolong", "group_name_toolong", "extra_data_size_toolong", "extra_field_underflow")

        private fun archive(vararg hex: String): ByteArray = hex.joinToString("").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun walk(bytes: ByteArray, mode: ReadMode, options: RarOptions = RarOptions()) =
        RarReader(ByteArrayInputStream(bytes), options.forMode(mode)).use { HostileRun.drive(it, mode, ByteArray(8192)) }

    @ParameterizedTest(name = "{0}, {1}, passwords {2}")
    @MethodSource("runs")
    fun `every fixture succeeds or fails with a RarException`(name: String, mode: ReadMode, passwords: Boolean) {
        val volumes = TestVolumes.fixture("libarchive/$name")
        val options = RarOptions(passwordProvider = if (passwords) libarchivePasswords(name) else null, volumeProvider = volumes)
        val result = SweepCheck.run(Fixtures.bytes("libarchive/$name"), mode, options, volumes)
        assertEquals(emptyList<String>(), result.violations, "$name ${result.outcome}")
    }

    @Test
    fun `fuzzing samples with an invalid first block fail in the constructor`() {
        for (sample in FIRST_BLOCK) {
            val stream = TrackingInputStream(Fixtures.stream("libarchive/${name(sample)}"))
            assertThrows<RarCorruptException>(sample) { RarReader(stream) }
            assertTrue(stream.closed, sample)
        }
        // RAR 7.23 refuses data_ready_pointer_leak as well.
        assertEquals(3, Fixtures.libarchive.getValue(name("data_ready_pointer_leak")).ltExitCode)
    }

    @Test
    fun `samples with an oversized owner name, extra area or record fail at the first entry`() {
        for (sample in FIRST_ENTRY) {
            RarReader(Fixtures.stream("libarchive/${name(sample)}")).use { reader ->
                assertThrows<RarCorruptException>(sample) { reader.nextEntry() }
            }
        }
    }

    @Test
    fun `bytes_remaining_underflow lists its entry, then fails reading or skipping it`() {
        // GH #2986: one packed byte, split after, followed by a header that runs past the end.
        val sample = name("bytes_remaining_underflow")
        RarReader(Fixtures.stream("libarchive/$sample")).use { reader ->
            val entry = reader.nextEntry()!!
            assertEquals("poc.txt", entry.name)
            assertEquals(10L, entry.size)
            assertTrue(entry.splitAcrossVolumes)
            // Without a volume provider the continuation is missing up front.
            assertEquals(1, assertThrows<RarMissingVolumeException> { reader.entryStream() }.volumeIndex)
            assertThrows<RarCorruptException> { reader.nextEntry() }
        }
        val volumes = TestVolumes.fixture("libarchive/$sample")
        RarReader(Fixtures.stream("libarchive/$sample"), RarOptions(volumeProvider = volumes)).use { reader ->
            reader.nextEntry()!!
            assertThrows<RarCorruptException> { reader.entryStream().readAllBytes() }
        }
        // The broken header is met before the next volume is asked for.
        assertEquals(emptyList<Int>(), volumes.opened)
    }

    @Test
    fun `loop_bug fails reading its entry on an oversized filter instead of looping`() {
        // test_read_format_rar5_loop_bug.c reads data blocks until the first non-OK result, then
        // expects the end of the archive. libarchive's parse_filter also rejects this filter, which
        // is longer than half the 128 KiB window, but its reader continues to the next header,
        // where this one stays failed.
        RarReader(Fixtures.stream("libarchive/${name("loop_bug")}")).use { reader ->
            assertEquals("a", reader.nextEntry()!!.name)
            val e = assertThrows<RarCorruptException> { reader.entryStream().readAllBytes() }
            assertEquals("Invalid filter (start 0, length 70000)", e.message)
            assertSame(e, assertThrows<IOException> { reader.nextEntry() }.cause)
        }
    }

    @Test
    fun `bad_filter fails bad_txt on its unsupported filter type, then reads ok_txt`() {
        // test_read_format_rar5_bad_filter.c: filter type 4 in bad.txt, then a stored ok.txt.
        val bytes = archive(
            "526172211a070100c51a333203010000", "88ecb099110202170004008004010762", "61642e747874c28c1400100000000000",
            "000001d67fc4bfe61000048000b1c330", "140f0202030003000001066f6b2e7478", "746f6b0a39f9b281020500",
        )
        RarReader(ByteArrayInputStream(bytes)).use { reader ->
            assertEquals("bad.txt", reader.nextEntry()!!.name)
            val e = assertThrows<RarUnsupportedException> { reader.entryStream().readAllBytes() }
            assertEquals(RarUnsupportedReason.UnknownFilter(4), e.reason)
            assertEquals("Unsupported filter type 4", e.message)
            assertEquals("ok.txt", reader.nextEntry()!!.name)
            assertEquals("ok\n", reader.entryStream().readAllBytes().decodeToString())
            assertNull(reader.nextEntry())
        }
        // Listed or skipped, its data is never decoded.
        for (mode in listOf(ReadMode.LIST_ONLY, ReadMode.SKIP)) {
            RarReader(ByteArrayInputStream(bytes), RarOptions().forMode(mode)).use { reader ->
                assertEquals(listOf("bad.txt", "ok.txt"), Fixtures.listAll(reader).map { it.name })
            }
        }
    }

    @Test
    fun `block_hdr_fail_loop ends with a corrupt file header`() {
        // test_read_format_rar5_block_hdr_fail_loop.c: a 3-byte file header whose fields run out.
        val bytes = archive("526172211a070100867efae703025620", "002015ae2100010800000100001500be", "c08000fff4")
        for (mode in ReadMode.entries) {
            assertThrows<RarCorruptException>("$mode") { walk(bytes, mode) }
        }
    }

    @Test
    fun `header_size_wraparound is a size field longer than 3 bytes`() {
        // test_read_format_rar5_header_size_wraparound.c: a size vint of nine 0xFF bytes and 0x01.
        val bytes = archive("526172211a070100", "901820eb", "ffffffffffffffffff01")
        assertEquals("Header size field too long", assertThrows<RarCorruptException> { RarReader(ByteArrayInputStream(bytes)) }.message)
    }

    @Test
    fun `truncated_htime is corrupt where libarchive reports the end of the archive`() {
        // test_read_format_rar5_truncated_htime.c: libarchive's parser runs out of data inside the
        // time record and returns ARCHIVE_EOF. The header's 3-byte extra area starts with a record
        // size of 0, which is corrupt here.
        val bytes = archive("526172211a07010001a1c16417020303", "000000000000016100030f0000000000", "00000000")
        for (mode in ReadMode.entries) {
            RarReader(ByteArrayInputStream(bytes), RarOptions().forMode(mode)).use { reader ->
                assertEquals("Extra record size exceeds extra area", assertThrows<RarCorruptException>("$mode") { reader.nextEntry() }.message)
            }
        }
    }

    @Test
    fun `varint_near_eof lists its entry`() {
        // test_read_format_rar5_varint_near_eof.c: an extra-area size right before the end header.
        val bytes = archive("526172211a070100c51a333203010000", "1ac720590d0203020100000000010161", "01640039f9b281020500")
        for (mode in listOf(ReadMode.LIST_ONLY, ReadMode.SKIP)) {
            RarReader(ByteArrayInputStream(bytes), RarOptions().forMode(mode)).use { reader ->
                assertEquals(listOf("a"), Fixtures.listAll(reader).map { it.name })
            }
        }
        // libarchive's test stops at the header. Its one packed byte disagrees with its size of 0.
        RarReader(ByteArrayInputStream(bytes)).use { reader ->
            assertEquals("a", reader.nextEntry()!!.name)
            assertThrows<RarCorruptException> { reader.entryStream() }
            assertThrows<IOException> { reader.nextEntry() }
        }
    }
}

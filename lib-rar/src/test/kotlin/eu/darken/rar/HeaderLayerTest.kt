package eu.darken.rar

import eu.darken.rar.RarBuilder.Companion.le32
import eu.darken.rar.RarBuilder.Companion.le64
import eu.darken.rar.RarBuilder.Companion.record
import eu.darken.rar.RarBuilder.Companion.vint
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import kotlin.time.Instant

class HeaderLayerTest {
    private fun list(bytes: ByteArray, options: RarOptions = RarOptions()): List<RarEntry> =
        RarReader(ByteArrayInputStream(bytes), options).use { Fixtures.listAll(it) }

    private fun single(extra: ByteArray, configure: RarBuilder.() -> Unit = {}): RarEntry {
        val builder = RarBuilder().main()
        builder.configure()
        return list(builder.file("a.txt", "abc".toByteArray(), extra = extra).end().build()).single()
    }

    @Test
    fun `vints are bounded`() {
        assertEquals(0x3FFF, HeaderCursor(byteArrayOf(0xFF.toByte(), 0x7F), 0, 2).vint())
        assertEquals(Long.MAX_VALUE, HeaderCursor(ByteArray(8) { 0xFF.toByte() } + byteArrayOf(0x7F), 0, 9).vint())
        assertEquals(Long.MAX_VALUE, HeaderCursor(ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x00), 0, 10).vint())
        assertThrows<RarCorruptException> { HeaderCursor(ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x01), 0, 10).vint() }
        assertThrows<RarCorruptException> { HeaderCursor(ByteArray(10) { 0x80.toByte() } + byteArrayOf(0), 0, 11).vint() }
        assertThrows<RarCorruptException> { HeaderCursor(byteArrayOf(0x80.toByte()), 0, 1).vint() }
    }

    @Test
    fun `header CRC mismatch is corrupt`() {
        val bad = RarBuilder().block(type = HeaderType.MAIN, fields = vint(0), crcDelta = 1).end().build()
        assertThrows<RarCorruptException> { RarReader(ByteArrayInputStream(bad)) }

        val fixture = Fixtures.bytes("rar723/hardlinks.rar")
        fixture[8 + 15] = (fixture[8 + 15].toInt() xor 0x01).toByte() // inside the first file header
        RarReader(ByteArrayInputStream(fixture)).use { reader ->
            assertThrows<RarCorruptException> { reader.nextEntry() }
        }
    }

    @Test
    fun `service headers are skipped`() {
        val bytes = RarBuilder().main()
            .file("CMT", "a comment".toByteArray(), service = true)
            .file("a.txt", "abc".toByteArray())
            .file("QO", ByteArray(64), service = true, extra = byteArrayOf(0x00)) // unparsed extra
            .end().build()
        assertEquals(listOf("a.txt"), list(bytes).map { it.name })
        for (name in listOf("comment.rar", "recovery.rar", "quickopen.rar")) {
            assertEquals(listOf("hello.txt", "numbers.txt"), Fixtures.list("rar723/$name").second.map { it.name }, name)
        }
    }

    @Test
    fun `unknown headers are skipped only when flagged`() {
        val skippable = RarBuilder().main()
            .block(type = 0x30, flags = HeaderFlags.SKIP_IF_UNKNOWN, fields = ByteArray(5), data = ByteArray(7))
            .file("a.txt", "abc".toByteArray()).end().build()
        assertEquals(listOf("a.txt"), list(skippable).map { it.name })

        val fatal = RarBuilder().main().block(type = 0x30, fields = ByteArray(5)).end().build()
        assertThrows<RarCorruptException> { list(fatal) }
    }

    @Test
    fun `archive without a main header gets default flags`() {
        val bytes = RarBuilder().file("a.txt", "abc".toByteArray()).end().build()
        RarReader(ByteArrayInputStream(bytes)).use { reader ->
            assertFalse(reader.archive.solid)
            assertFalse(reader.archive.multiVolume)
            assertEquals("a.txt", reader.nextEntry()!!.name)
            assertEquals("abc", String(reader.entryStream().readAllBytes()))
            assertNull(reader.nextEntry())
        }
    }

    @Test
    fun `main header flags`() {
        val bytes = RarBuilder().main(flags = 0x1D, extra = record(1, vint(3) + vint(100) + vint(200))).end().build()
        RarReader(ByteArrayInputStream(bytes)).use { reader ->
            assertEquals(RarArchiveInfo(true, true, 0, false, true, true), reader.archive)
            assertNull(reader.nextEntry())
        }
        val second = RarBuilder().main(flags = 0x01, volumeNumber = 1).end().build()
        val e = assertThrows<RarUnsupportedException> { RarReader(ByteArrayInputStream(second)) }
        assertEquals(RarUnsupportedReason.NotFirstVolume(1), e.reason)
    }

    @Test
    fun `end of first volume with more volumes needs the next volume`() {
        val bytes = RarBuilder().main(flags = 0x01)
            .file("a.bin", "abc".toByteArray(), blockFlags = HeaderFlags.SPLIT_AFTER)
            .end(moreVolumes = true).build()
        RarReader(ByteArrayInputStream(bytes)).use { reader ->
            val entry = reader.nextEntry()!!
            assertTrue(entry.splitAcrossVolumes)
            assertEquals(3, entry.packedSize)
            assertNull(entry.checksum)
            assertEquals(1, assertThrows<RarMissingVolumeException> { reader.nextEntry() }.volumeIndex)
        }
    }

    @Test
    fun `end header without its flags field is accepted`() {
        val bytes = RarBuilder().main().file("a.txt", "abc".toByteArray()).block(type = HeaderType.ENDARC).build()
        assertEquals(1, list(bytes).size)
    }

    @Test
    fun `continuation header in the first volume is corrupt`() {
        val bytes = RarBuilder().main().file("a.bin", "abc".toByteArray(), blockFlags = HeaderFlags.SPLIT_BEFORE).end().build()
        assertThrows<RarCorruptException> { list(bytes) }
    }

    @Test
    fun `archive encryption header after the start is corrupt`() {
        val crypt = vint(0) + vint(0) + byteArrayOf(15) + ByteArray(16)
        val bytes = RarBuilder().main().block(type = HeaderType.CRYPT, fields = crypt).end().build()
        assertThrows<RarCorruptException> { list(bytes) }
        val first = RarBuilder().block(type = HeaderType.CRYPT, fields = crypt).build()
        assertEquals(RarPasswordScope.HEADERS, assertThrows<RarPasswordRequiredException> { list(first, RarOptions(passwordProvider = null)) }.scope)
    }

    @Test
    fun `directory with data is corrupt and file without data flag is corrupt`() {
        val dirWithData = RarBuilder().main().file("d", "x".toByteArray(), directory = true, size = 0, crc = null).end().build()
        assertThrows<RarCorruptException> { list(dirWithData) }
        val noDataFlag = RarBuilder().main()
            .block(type = HeaderType.FILE, fields = vint(0) + vint(0) + vint(0) + vint(0) + vint(1) + vint(1) + "a".toByteArray())
            .end().build()
        assertThrows<RarCorruptException> { list(noDataFlag) }
    }

    @Test
    fun `unix times with nanoseconds`() {
        // unix | mtime | ctime | atime | nanoseconds
        val payload = vint(0x1F) +
            le32(1_700_000_000) + le32(1_600_000_000) + le32(1_500_000_000) +
            le32(123_456_789) + le32(0x40000000 or 5) + le32(0x3FFFFFFF)
        val entry = single(record(3, payload))
        assertEquals(Instant.fromEpochSeconds(1_700_000_000, 123_456_789), entry.mtime)
        assertEquals(Instant.fromEpochSeconds(1_600_000_000, 5), entry.ctime) // reserved top bits masked
        assertEquals(Instant.fromEpochSeconds(1_500_000_000), entry.atime) // out-of-range nanoseconds dropped
    }

    @Test
    fun `windows FILETIME`() {
        // 2023-11-14T22:13:20.1234567Z = (1_700_000_000 + 11_644_473_600) s plus 1_234_567 ticks of 100 ns.
        val ticks = (1_700_000_000L + 11_644_473_600L) * 10_000_000 + 1_234_567
        val entry = single(record(3, vint(0x02) + le64(ticks)))
        assertEquals(Instant.fromEpochSeconds(1_700_000_000, 123_456_700), entry.mtime)
        assertNull(entry.ctime)
        assertNull(entry.atime)
    }

    @Test
    fun `header unix mtime is used without a time record`() {
        val bytes = RarBuilder().main().file("a.txt", "abc".toByteArray(), unixMtime = 1_234_567_890).end().build()
        assertEquals(Instant.fromEpochSeconds(1_234_567_890), list(bytes).single().mtime)
    }

    @Test
    fun `version, owner and unknown records`() {
        val owner = record(6, vint(0x0F) + vint(4) + "user".toByteArray() + vint(5) + "group".toByteArray() + vint(1000) + vint(100))
        val unknown = record(0x7F, ByteArray(10))
        val entry = single(record(4, vint(0) + vint(7)) + owner + unknown)
        assertEquals(7, entry.version)
        assertEquals("a.txt", entry.name)
    }

    @Test
    fun `malformed records are corrupt`() {
        val cases = listOf(
            byteArrayOf(0x00, 0x05), // zero record size
            byteArrayOf(0x09, 0x02), // record larger than the extra area
            record(6, vint(0x01) + vint(50) + "short".toByteArray()), // owner name beyond record
            record(5, vint(1) + vint(0) + vint(0)), // empty link target
            record(5, vint(1) + vint(0) + vint(20) + "short".toByteArray()), // link target beyond record
            record(2, vint(0) + ByteArray(16)), // truncated BLAKE2sp
            record(3, vint(0x03) + byteArrayOf(1, 2)), // truncated unix time
            record(4, vint(0) + vint(Int.MAX_VALUE.toLong() + 1)), // version too large
        )
        for ((i, extra) in cases.withIndex()) {
            assertThrows<RarCorruptException>("case $i") { single(extra) }
        }
    }

    @Test
    fun `redirection records`() {
        fun redirect(type: Long, flags: Long, target: String) =
            record(5, vint(type) + vint(flags) + vint(target.toByteArray().size.toLong()) + target.toByteArray())

        val expected = mapOf(
            1L to RarRedirectionType.UNIX_SYMLINK,
            2L to RarRedirectionType.WINDOWS_SYMLINK,
            3L to RarRedirectionType.JUNCTION,
            4L to RarRedirectionType.HARDLINK,
            5L to RarRedirectionType.FILE_COPY,
            9L to RarRedirectionType.UNKNOWN,
        )
        for ((type, kind) in expected) {
            val entry = single(redirect(type, 1, "target/ü"))
            assertEquals(RarRedirection(kind, "target/ü", true), entry.redirection)
        }
    }

    @Test
    fun `hash and encryption records`() {
        val digest = ByteArray(32) { it.toByte() }
        val entry = single(record(2, vint(0) + digest))
        assertArrayEquals(digest, (entry.checksum as RarChecksum.Blake2sp).digest)
        assertEquals(RarChecksum.Crc32(RarBuilder.crc("abc".toByteArray())), single(record(2, vint(5) + ByteArray(4))).checksum)

        val crypt = record(1, vint(0) + vint(0x01) + byteArrayOf(15) + ByteArray(16) + ByteArray(16) + ByteArray(12))
        assertTrue(single(crypt).isEncrypted)
        assertFalse(single(ByteArray(0)).isEncrypted)
    }

    @Test
    fun `host OS and attributes`() {
        val windows = RarBuilder().main().file("a.txt", "abc".toByteArray(), hostOs = 0, attributes = 0x21).end().build()
        list(windows).single().let {
            assertEquals(RarHostOs.WINDOWS, it.hostOs)
            assertEquals(0x21L, it.attributes)
        }
        val other = RarBuilder().main().file("a.txt", "abc".toByteArray(), hostOs = 7).end().build()
        assertEquals(RarHostOs.OTHER, list(other).single().hostOs)
    }

    @Test
    fun `limits are enforced`() {
        fun reason(block: () -> Unit) = (assertThrows<RarUnsupportedException> { block() }.reason as RarUnsupportedReason.LimitExceeded).limit

        val longName = RarBuilder().main().file("x".repeat(100), "abc".toByteArray()).end().build()
        assertEquals("maxNameBytes", reason { list(longName, RarOptions(limits = RarLimits(maxNameBytes = 99))) })
        assertEquals(1, list(longName, RarOptions(limits = RarLimits(maxNameBytes = 100))).size)

        val three = RarBuilder().main().file("a", "1".toByteArray()).file("b", "2".toByteArray()).file("c", "3".toByteArray()).end().build()
        assertEquals("maxEntries", reason { list(three, RarOptions(limits = RarLimits(maxEntries = 2))) })

        val records = record(0x7F, ByteArray(1)) + record(0x7F, ByteArray(1)) + record(0x7F, ByteArray(1))
        val manyRecords = RarBuilder().main().file("a", "1".toByteArray(), extra = records).end().build()
        assertEquals("maxExtraRecords", reason { list(manyRecords, RarOptions(limits = RarLimits(maxExtraRecords = 2))) })

        val big = RarBuilder().main().file("a".repeat(300), "1".toByteArray()).end().build()
        assertEquals("maxHeaderSize", reason { list(big, RarOptions(limits = RarLimits(maxHeaderSize = 200))) })
    }

    @Test
    fun `empty and unicode names`() {
        val names = listOf("Ελληνικά/日本語 😀.txt", "русский")
        val bytes = RarBuilder().main().apply { names.forEach { file(it, "x".toByteArray()) } }.end().build()
        assertEquals(names, list(bytes).map { it.name })
        assertThrows<RarCorruptException> { list(RarBuilder().main().file("", "x".toByteArray()).end().build()) }
    }
}

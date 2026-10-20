package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import kotlin.time.Instant

class FixtureListingTest {
    companion object {
        @JvmStatic
        fun singleVolume(): List<String> = Fixtures.rar723.fixtures.values
            .filter { !it.isVolumeSet && !it.headerEncrypted }
            .map { it.name }

        @JvmStatic
        fun headerEncrypted(): List<String> = Fixtures.rar723.fixtures.values
            .filter { it.headerEncrypted }
            .map { it.files.first() }

        @JvmStatic
        fun volumeSets(): List<String> = Fixtures.rar723.fixtures.values
            .filter { it.isVolumeSet && !it.headerEncrypted }
            .map { it.name }

        private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun assertMatches(fixture: ManifestFixture, expected: ManifestEntry, actual: RarEntry) {
        val where = "${fixture.name} #${expected.ordinal} ${expected.name}"
        assertEquals(expected.ordinal, actual.ordinal, where)
        assertEquals(expected.name, actual.name, where)
        assertEquals(expected.size, actual.size, where)
        assertEquals(expected.packedSize ?: 0L, actual.packedSize, where)
        assertEquals(expected.version, actual.version, where)
        assertEquals(expected.encrypted, actual.isEncrypted, where)
        assertEquals(expected.solid, actual.isSolid, where)
        assertEquals(expected.splitAcrossVolumes, actual.splitAcrossVolumes, where)
        assertEquals(RarHostOs.UNIX, actual.hostOs, where)
        expected.mtimeNs?.let {
            assertEquals(Instant.fromEpochSeconds(it / 1_000_000_000, it % 1_000_000_000), actual.mtime, where)
        }

        when (expected.type) {
            "directory" -> {
                assertTrue(actual.isDirectory, where)
                assertNull(actual.redirection, where)
            }
            "file" -> {
                assertFalse(actual.isDirectory, where)
                assertNull(actual.redirection, where)
            }
            "symlink" -> assertEquals(RarRedirectionType.UNIX_SYMLINK, actual.redirection?.type, where)
            "hardlink" -> assertEquals(RarRedirectionType.HARDLINK, actual.redirection?.type, where)
            "filecopy" -> assertEquals(RarRedirectionType.FILE_COPY, actual.redirection?.type, where)
            else -> error("Unknown type ${expected.type}")
        }
        if (expected.type in setOf("symlink", "hardlink", "filecopy")) {
            assertEquals(expected.linkTarget, actual.redirection?.target, where)
        }

        // A split entry's first part stores the CRC of its packed bytes, which is not the file's.
        if (expected.splitAcrossVolumes) {
            assertNull(actual.checksum, where)
            return
        }
        when (val label = expected.ltChecksum?.first()) {
            null -> assertNull(actual.checksum, where)
            "CRC32", "CRC32 MAC", "Pack-CRC32" -> {
                val value = expected.ltChecksum!![1].toLong(16).toInt()
                assertEquals(RarChecksum.Crc32(value), actual.checksum, where)
                // Independent of rar lt: the CRC of the source file, for whole plain entries.
                if (label == "CRC32" && expected.type == "file" && expected.crc32 != null) {
                    assertEquals(expected.crc32.toLong(16).toInt(), value, where)
                }
            }
            "BLAKE2" -> {
                val digest = (actual.checksum as RarChecksum.Blake2sp).digest
                assertArrayEquals(hex(expected.ltChecksum!![1]), digest, where)
            }
            else -> error("Unknown checksum label $label")
        }
    }

    @ParameterizedTest
    @MethodSource("singleVolume")
    fun `single-volume fixture lists per manifest`(name: String) {
        val fixture = Fixtures.rar723.fixtures.getValue(name)
        val (info, entries) = Fixtures.list(Fixtures.rar723.path(fixture.files.single()))
        assertEquals(fixture.solid, info.solid, name)
        assertFalse(info.multiVolume, name)
        assertEquals(0, info.volumeNumber, name)
        assertEquals("lock" in fixture.details, info.locked, name)
        assertEquals("recovery record" in fixture.details, info.hasRecoveryRecord, name)
        assertEquals(fixture.entries.size, entries.size, name)
        fixture.entries.zip(entries).forEach { (expected, actual) -> assertMatches(fixture, expected, actual) }
    }

    @ParameterizedTest
    @MethodSource("singleVolume")
    fun `listOnly lists the same entries`(name: String) {
        val file = Fixtures.rar723.path(Fixtures.rar723.fixtures.getValue(name).files.single())
        assertEquals(Fixtures.list(file).second, Fixtures.list(file, RarOptions(listOnly = true)).second)
    }

    @ParameterizedTest
    @MethodSource("headerEncrypted")
    fun `header-encrypted archive needs a password before the first entry`(file: String) {
        val stream = TrackingInputStream(Fixtures.stream(Fixtures.rar723.path(file)))
        val e = assertThrows<RarPasswordRequiredException> { RarReader(stream, RarOptions(passwordProvider = null)) }
        assertEquals(RarPasswordScope.HEADERS, e.scope)
        assertNull(e.ordinal)
        assertNull(e.entryName)
        assertTrue(stream.closed)
    }

    @ParameterizedTest
    @MethodSource("volumeSets")
    fun `volume set without a volume provider lists its first volume, then needs the second`(name: String) {
        val fixture = Fixtures.rar723.fixtures.getValue(name)
        RarReader(Fixtures.stream(Fixtures.rar723.path(fixture.files.first()))).use { reader ->
            val info = reader.archive
            assertTrue(info.multiVolume, name)
            assertEquals(0, info.volumeNumber, name)
            assertEquals(fixture.solid, info.solid, name)
            val expected = fixture.firstVolumeEntries
            val entries = expected.map { reader.nextEntry() ?: fail("$name ended before ${it.name}") }
            expected.zip(entries).forEach { (e, a) -> assertMatches(fixture, e, a) }
            assertTrue(entries.last().splitAcrossVolumes, name)
            assertEquals(1, assertThrows<RarMissingVolumeException>(name) { reader.nextEntry() }.volumeIndex)
        }
    }

    @Test
    fun `later volumes are refused`() {
        val files = mapOf(
            "rar723/vol-plain.part2.rar" to 1,
            "rar723/vol-legacy.r01" to 2,
            "libarchive/test_read_format_rar5_multiarchive.part03.rar" to 2,
        )
        for ((file, number) in files) {
            val e = assertThrows<RarUnsupportedException>(file) { RarReader(Fixtures.stream(file)) }
            assertEquals(RarUnsupportedReason.NotFirstVolume(number), e.reason, file)
        }
    }

    @Test
    fun `split entry has no data without its next volume`() {
        RarReader(Fixtures.stream("rar723/vol-plain.part1.rar")).use { reader ->
            val entry = reader.nextEntry()!!
            assertTrue(entry.splitAcrossVolumes)
            val e = assertThrows<RarMissingVolumeException> { reader.entryStream() }
            assertEquals(1, e.volumeIndex)
            // The reader stays usable until it needs the volume itself; then it fails for good.
            assertEquals(1, assertThrows<RarMissingVolumeException> { reader.nextEntry() }.volumeIndex)
            val later = assertThrows<IOException> { reader.nextEntry() }
            assertTrue(later.cause is RarMissingVolumeException)
        }
    }

    @Test
    fun `huge dictionary archive lists but its data is refused`() {
        RarReader(Fixtures.stream("rar723/dict-5g-v1.rar")).use { reader ->
            val entry = reader.nextEntry()!!
            assertEquals("zero.bin", entry.name)
            assertEquals(4400L * 1024 * 1024, entry.size)
            val e = assertThrows<RarUnsupportedException> { reader.entryStream() }
            assertEquals(RarUnsupportedReason.AlgorithmVersion(1), e.reason)
            assertNull(reader.nextEntry())
        }
    }

    @Test
    fun `per-file passwords and same-name versions`() {
        val (_, entries) = Fixtures.list("rar723/per-file-passwords.rar")
        assertEquals(listOf("a.txt", "b.txt", "c.txt", "same.txt", "same.txt"), entries.map { it.name })
        assertEquals(listOf(true, true, false, true, true), entries.map { it.isEncrypted })
        assertEquals(listOf(null, null, null, 1, null), entries.map { it.version })
        assertEquals(listOf(0, 1, 2, 3, 4), entries.map { it.ordinal })
    }

    @Test
    fun `stored fixture carries all three timestamps`() {
        val (_, entries) = Fixtures.list("rar723/stored.rar")
        for (entry in entries) {
            assertNotNull(entry.mtime, entry.name)
            assertNotNull(entry.ctime, entry.name)
            assertNotNull(entry.atime, entry.name)
        }
    }
}

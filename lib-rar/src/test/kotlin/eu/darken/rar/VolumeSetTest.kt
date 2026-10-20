package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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

class VolumeSetTest {
    companion object {
        @JvmStatic
        fun sets(): List<String> = Fixtures.rar723.fixtures.values.filter { it.isVolumeSet }.map { it.name }

        private const val PLAIN = "rar723/vol-plain.part1.rar"
        private const val MULTI = "libarchive/test_read_format_rar5_multiarchive.part01.rar"
        private const val MULTI_SOLID = "libarchive/test_read_format_rar5_multiarchive_solid.part01.rar"
        private const val BSDCAT = "home/antek/temp/build/unrar5/libarchive/bin/bsdcat_test"
        private const val BSDTAR = "home/antek/temp/build/unrar5/libarchive/bin/bsdtar_test"

        /** multiarchive_solid's entries and CRCs, from libarchive's test_read_format_rar5_multiarchive_solid_extr_all. */
        private val SOLID_CRCS = listOf(
            "cebula.txt" to 0x7E5EC49EL, "test.bin" to 0x7CCA70CDL, "test1.bin" to 0x7E13B2C6L,
            "test2.bin" to 0xF166AFCBL, "test3.bin" to 0x9FB123D9L, "test4.bin" to 0x10C43ED4L,
            "test5.bin" to 0xB9D155F2L, "test6.bin" to 0x36A448FFL, "elf-Linux-ARMv7-ls" to 0x886F91EBL,
        )

        private fun crc(data: ByteArray): Long = CRC32().apply { update(data) }.value

        private fun bytes(firstPath: String): List<ByteArray> = Fixtures.volumeSet(firstPath).map { Fixtures.bytes(it) }
    }

    private class Cancelled : RuntimeException()

    private class BalanceBudget : RarMemoryBudget {
        var balance = 0L

        override fun acquire(bytes: Long) {
            balance += bytes
        }

        override fun release(bytes: Long) {
            balance -= bytes
        }
    }

    /** The fixture's volumes, located by deriving each name from the first one. */
    private fun volumes(fixture: ManifestFixture): TestVolumes {
        val paths = Fixtures.volumeSet(Fixtures.rar723.path(fixture.files.first()))
        assertEquals(fixture.files.map { Fixtures.rar723.path(it) }, paths, fixture.name)
        return TestVolumes(paths.map { Fixtures.bytes(it) })
    }

    private fun options(
        fixture: ManifestFixture,
        volumes: TestVolumes,
        budget: RarMemoryBudget = RarMemoryBudget.UNLIMITED,
        listOnly: Boolean = false,
    ) = RarOptions(
        passwordProvider = fixture.listPassword?.let { password -> RarPasswordProvider { password.toCharArray() } },
        volumeProvider = volumes,
        memoryBudget = budget,
        listOnly = listOnly,
    )

    private fun RarReader.readCurrent(): ByteArray = entryStream().use { it.readAllBytes() }

    /**
     * Lists [volumes] (reading every entry if [read]) and returns what that threw, after checking
     * that every stream the provider handed out was closed and the memory budget is balanced.
     */
    private fun failure(
        volumes: TestVolumes,
        read: Boolean,
        configure: (RarMemoryBudget) -> RarOptions = { RarOptions(volumeProvider = volumes, memoryBudget = it) },
    ): Throwable {
        val budget = BalanceBudget()
        val thrown = runCatching {
            RarReader(volumes.first(), configure(budget)).use { reader ->
                while (reader.nextEntry() != null) if (read) reader.readCurrent()
            }
        }.exceptionOrNull() ?: fail<Throwable>("No failure")
        assertTrue(volumes.allClosed, "A volume stream was left open")
        assertEquals(0L, budget.balance)
        return thrown
    }

    /** Volume [index] of [count], holding one part of the split entry [name]. */
    private fun partVolume(
        index: Int,
        count: Int,
        name: String,
        part: ByteArray,
        size: Long?,
        crc: Int?,
        extra: ByteArray = ByteArray(0),
        volumeNumber: Long? = if (index == 0) null else index.toLong(),
    ): ByteArray {
        val last = index == count - 1
        var flags = 0L
        if (index > 0) flags = flags or HeaderFlags.SPLIT_BEFORE
        if (!last) flags = flags or HeaderFlags.SPLIT_AFTER
        return RarBuilder().main(flags = 0x01, volumeNumber = volumeNumber)
            .file(name, part, size = size, crc = crc, extra = extra, blockFlags = flags)
            .end(moreVolumes = !last).build()
    }

    private fun cut(data: ByteArray, vararg at: Int): List<ByteArray> =
        (listOf(0) + at.toList() + data.size).zipWithNext { from, to -> data.copyOfRange(from, to) }

    /** Stored [data] split into [parts]; each part but the last stores [partCrc] of its bytes, the last the file's CRC. */
    private fun storedSet(
        data: ByteArray,
        parts: List<ByteArray>,
        size: Long? = data.size.toLong(),
        partCrc: (ByteArray) -> Int = { RarBuilder.crc(it) },
    ): List<ByteArray> = parts.mapIndexed { i, part ->
        partVolume(i, parts.size, "s.bin", part, size, if (i == parts.lastIndex) RarBuilder.crc(data) else partCrc(part))
    }

    @ParameterizedTest
    @MethodSource("sets")
    fun `volume set lists across its volumes`(name: String) {
        val fixture = Fixtures.rar723.fixtures.getValue(name)
        val volumes = volumes(fixture)
        val entries = RarReader(volumes.first(), options(fixture, volumes)).use { reader ->
            assertTrue(reader.archive.multiVolume, name)
            assertEquals(0, reader.archive.volumeNumber, name)
            assertEquals(fixture.solid, reader.archive.solid, name)
            assertEquals(fixture.headerEncrypted, reader.archive.headerEncrypted, name)
            Fixtures.listAll(reader)
        }
        val expected = fixture.entries.filter { !it.continuation }
        assertEquals(expected.map { it.name }, entries.map { it.name }, name)
        expected.zip(entries).forEach { (e, a) ->
            val where = "$name #${e.ordinal} ${e.name}"
            assertEquals(e.ordinal, a.ordinal, where)
            assertEquals(e.size, a.size, where)
            // A split entry lists its first part, whose stored CRC covers only that part's packed bytes.
            assertEquals(e.packedSize, a.packedSize, where)
            assertEquals(e.splitAcrossVolumes, a.splitAcrossVolumes, where)
            assertEquals(e.encrypted, a.isEncrypted, where)
            assertEquals(e.solid, a.isSolid, where)
            if (e.splitAcrossVolumes) {
                assertNull(a.checksum, where)
            } else {
                assertEquals("CRC32", e.ltChecksum!![0], where)
                assertEquals(RarChecksum.Crc32(e.ltChecksum[1].toLong(16).toInt()), a.checksum, where)
            }
        }
        assertEquals(fixture.files.indices.toList(), volumes.opened, name)
        assertTrue(volumes.allClosed, name)
    }

    @ParameterizedTest
    @MethodSource("sets")
    fun `every entry of a volume set reads with verified checksums`(name: String) {
        val fixture = Fixtures.rar723.fixtures.getValue(name)
        val volumes = volumes(fixture)
        val budget = BalanceBudget()
        RarReader(volumes.first(), options(fixture, volumes, budget)).use { reader ->
            for (expected in fixture.entries.filter { !it.continuation }) {
                val entry = reader.nextEntry() ?: fail("$name ended before ${expected.name}")
                val data = reader.readCurrent()
                assertEquals(expected.size, data.size.toLong(), "$name ${entry.name}")
                assertEquals(expected.sha256, Fixtures.sha256(data), "$name ${entry.name}")
                // The whole file's CRC, independent of rar lt.
                assertEquals(expected.crc32!!.toLong(16), crc(data), "$name ${entry.name}")
            }
            assertNull(reader.nextEntry())
        }
        assertEquals(fixture.files.indices.toList(), volumes.opened, name)
        assertTrue(volumes.allClosed, name)
        assertEquals(0L, budget.balance, name)
    }

    @ParameterizedTest
    @MethodSource("sets")
    fun `listOnly lists a volume set like a full listing`(name: String) {
        val fixture = Fixtures.rar723.fixtures.getValue(name)
        val full = volumes(fixture).let { v -> RarReader(v.first(), options(fixture, v)).use { Fixtures.listAll(it) } }
        val volumes = volumes(fixture)
        val listed = RarReader(volumes.first(), options(fixture, volumes, listOnly = true)).use { Fixtures.listAll(it) }
        assertEquals(full, listed, name)
        assertEquals(fixture.files.indices.toList(), volumes.opened, name)
        assertTrue(volumes.allClosed, name)
    }

    @ParameterizedTest
    @MethodSource("sets")
    fun `a partly read split entry is skipped through its later parts`(name: String) {
        val fixture = Fixtures.rar723.fixtures.getValue(name)
        val volumes = volumes(fixture)
        RarReader(volumes.first(), options(fixture, volumes)).use { reader ->
            val first = reader.nextEntry()!!
            assertTrue(first.splitAcrossVolumes, name)
            assertEquals(100, reader.entryStream().readNBytes(100).size, name)
            val rest = fixture.entries.filter { !it.continuation }.drop(1)
            for (expected in rest) {
                assertEquals(expected.name, reader.nextEntry()!!.name, name)
                assertEquals(expected.sha256, Fixtures.sha256(reader.readCurrent()), "$name ${expected.name}")
            }
            assertNull(reader.nextEntry())
        }
        assertTrue(volumes.allClosed, name)
    }

    @Test
    fun `the header key carries over to later volumes`() {
        val fixture = Fixtures.rar723.fixtures.getValue("vol-hp.part1.rar")
        val volumes = volumes(fixture)
        val provider = RecordingProvider("password")
        RarReader(volumes.first(), RarOptions(passwordProvider = provider, volumeProvider = volumes)).use { reader ->
            for (expected in fixture.entries.filter { !it.continuation }) {
                assertEquals(expected.name, reader.nextEntry()!!.name)
                assertEquals(expected.sha256, Fixtures.sha256(reader.readCurrent()))
            }
            assertNull(reader.nextEntry())
        }
        assertEquals(1, provider.requests.count { it.scope == RarPasswordScope.HEADERS })
        provider.assertWiped()
    }

    @Test
    fun `a later volume whose encryption header rejects the key asks for the header password again`() {
        val parts = bytes("rar723/vol-hp.part1.rar").toMutableList()
        val second = parts[1].copyOf()
        val crypt = RarBlocks.cryptHeader(second)
        val salt = checkNotNull(CryptHeader.parse(RarBlocks.header(second, crypt)).salt)
        val at = RarBlocks.find(second, salt, crypt.start, crypt.headerEnd)
        second[at] = (second[at].toInt() xor 0x01).toByte()
        RarBlocks.reseal(second, crypt)
        parts[1] = second

        fun attempt(secondAnswer: String?): Pair<Throwable, RecordingProvider> {
            var headerRequests = 0
            val provider = RecordingProvider { request ->
                if (request.scope == RarPasswordScope.HEADERS && ++headerRequests > 1) secondAnswer else "password"
            }
            val volumes = TestVolumes(parts)
            val e = failure(volumes, read = false) { RarOptions(passwordProvider = provider, volumeProvider = volumes, memoryBudget = it) }
            assertEquals(2, provider.requests.count { it.scope == RarPasswordScope.HEADERS })
            assertEquals(listOf(0, 1), volumes.opened)
            return e to provider
        }

        val (wrong, _) = attempt("password")
        assertTrue(wrong is RarWrongPasswordException, "$wrong")
        assertEquals(RarPasswordScope.HEADERS, (wrong as RarWrongPasswordException).scope)
        assertTrue(wrong.certain)

        val (missing, _) = attempt(null)
        assertTrue(missing is RarPasswordRequiredException, "$missing")
        assertEquals(RarPasswordScope.HEADERS, (missing as RarPasswordRequiredException).scope)
    }

    @Test
    fun `libarchive multiarchive extracts as libarchive expects`() {
        val volumes = TestVolumes.fixture(MULTI)
        RarReader(volumes.first(), RarOptions(volumeProvider = volumes)).use { reader ->
            assertEquals(BSDCAT, reader.nextEntry()!!.name)
            assertEquals(0x35277473L, crc(reader.readCurrent()))
            assertEquals(BSDTAR, reader.nextEntry()!!.name)
            assertEquals(0xE59665F8L, crc(reader.readCurrent()))
            assertNull(reader.nextEntry())
        }
        assertEquals((0..7).toList(), volumes.opened)
        assertTrue(volumes.allClosed)

        // test_read_format_rar5_multiarchive_skip_all and _skip_all_but_second.
        val skipping = TestVolumes.fixture(MULTI)
        RarReader(skipping.first(), RarOptions(volumeProvider = skipping)).use { reader ->
            assertEquals(BSDCAT, reader.nextEntry()!!.name)
            assertEquals(BSDTAR, reader.nextEntry()!!.name)
            assertEquals(0xE59665F8L, crc(reader.readCurrent()))
            assertNull(reader.nextEntry())
        }
        assertEquals((0..7).toList(), skipping.opened)
        assertTrue(skipping.allClosed)
    }

    @Test
    fun `libarchive solid multiarchive extracts as libarchive expects`() {
        val budget = BalanceBudget()
        val volumes = TestVolumes.fixture(MULTI_SOLID)
        RarReader(volumes.first(), RarOptions(volumeProvider = volumes, memoryBudget = budget)).use { reader ->
            for ((name, expected) in SOLID_CRCS) {
                assertEquals(name, reader.nextEntry()!!.name)
                assertEquals(expected, crc(reader.readCurrent()), name)
            }
            assertNull(reader.nextEntry())
        }
        assertEquals((0..3).toList(), volumes.opened)
        assertTrue(volumes.allClosed)
        assertEquals(0L, budget.balance)

        // test_read_format_rar5_multiarchive_solid_skip_all_but_last: skipped solid entries are decoded.
        val skipping = TestVolumes.fixture(MULTI_SOLID)
        RarReader(skipping.first(), RarOptions(volumeProvider = skipping)).use { reader ->
            for ((name, expected) in SOLID_CRCS) {
                assertEquals(name, reader.nextEntry()!!.name)
                if (name == SOLID_CRCS.last().first) assertEquals(expected, crc(reader.readCurrent()), name)
            }
            assertNull(reader.nextEntry())
        }
        assertTrue(skipping.allClosed)
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `libarchive's damaged multi-volume fixture fails with a RarException`() {
        val volumes = TestVolumes.fixture("libarchive/test_read_format_rar5_bad_window_sz_in_mltarc_file.rar")
        val e = failure(volumes, read = true)
        assertTrue(e is RarException, "$e")
    }

    @Test
    fun `without a provider the first transition needs volume 1`() {
        // The first volume ends after a whole entry.
        val set = RarBuilder().main(flags = 0x01).file("a.txt", "abc".toByteArray()).end(moreVolumes = true).build()
        val volumes = TestVolumes(listOf(set))
        RarReader(volumes.first(), RarOptions()).use { reader ->
            assertEquals("a.txt", reader.nextEntry()!!.name)
            assertEquals(1, assertThrows<RarMissingVolumeException> { reader.nextEntry() }.volumeIndex)
            assertTrue(assertThrows<IOException> { reader.nextEntry() }.cause is RarMissingVolumeException)
        }
        assertTrue(volumes.allClosed)

        // The first volume ends inside an entry, whether it is read or skipped.
        for (read in listOf(true, false)) {
            val plain = TestVolumes(bytes(PLAIN))
            val e = failure(plain, read) { RarOptions(memoryBudget = it) }
            assertEquals(1, (e as RarMissingVolumeException).volumeIndex)
            assertEquals(listOf(0), plain.opened)
        }
    }

    @Test
    fun `a provider without the middle or the last volume fails where it is needed`() {
        val parts = bytes(PLAIN)
        for (missing in listOf(1, 2)) {
            for (read in listOf(true, false)) {
                val volumes = TestVolumes { index -> if (index == missing) null else parts.getOrNull(index) }
                val e = failure(volumes, read)
                assertTrue(e is RarMissingVolumeException, "volume $missing, read $read: $e")
                assertEquals(missing, (e as RarMissingVolumeException).volumeIndex)
                assertEquals((0..missing).toList(), volumes.opened)
            }
        }
    }

    @Test
    fun `repeated, swapped, unrelated and mismatched volumes are corrupt`() {
        val (p1, p2, p3) = bytes(PLAIN)
        val (hp1, hp2, hp3) = bytes("rar723/vol-hp.part1.rar")
        val cases = listOf(
            Triple("the first volume again as volume 1", listOf(p1, p1, p3), 1),
            Triple("volume 1 again as volume 2", listOf(p1, p2, p2), 2),
            Triple("volumes 1 and 2 swapped", listOf(p1, p3, p2), 1),
            Triple("a single-volume archive as volume 1", listOf(p1, Fixtures.bytes("rar723/stored.rar"), p3), 1),
            Triple("not an archive as volume 1", listOf(p1, ByteArray(64), p3), 1),
            Triple("encrypted headers in volume 1 only", listOf(p1, hp2, p3), 1),
            Triple("plain headers in volume 1 only", listOf(hp1, p2, hp3), 1),
        )
        for ((label, list, index) in cases) {
            for (read in listOf(true, false)) {
                val volumes = TestVolumes(list)
                val e = failure(volumes, read) {
                    RarOptions(passwordProvider = { "password".toCharArray() }, volumeProvider = volumes, memoryBudget = it)
                }
                assertTrue(e is RarCorruptException, "$label, read $read: $e")
                assertTrue(e.message!!.contains("Volume $index"), "$label, read $read: ${e.message}")
            }
        }
    }

    @Test
    fun `a volume declaring the wrong volume number is corrupt`() {
        val data = ByteArray(600) { (it * 5).toByte() }
        val parts = cut(data, 200, 400)
        val good = storedSet(data, parts)
        val set = good.toMutableList()
        set[2] = partVolume(2, 3, "s.bin", parts[2], 600, RarBuilder.crc(data), volumeNumber = 3)
        for (read in listOf(true, false)) {
            val e = failure(TestVolumes(set), read)
            assertTrue(e is RarCorruptException, "$e")
            assertTrue(e.message!!.contains("Volume 2 declares volume number 3"), e.message)
        }
    }

    @Test
    fun `a truncated volume is corrupt`() {
        val (p1, p2, p3) = bytes(PLAIN)
        // Inside random.bin's last part, inside lorem.txt's first part, and before the end header.
        val blocks = RarBlocks.parse(p2)
        val endHeader = blocks.single { it.type == HeaderType.ENDARC }.start
        for (length in listOf(5000, RarBlocks.file(p2, "lorem.txt").headerEnd + 100, endHeader)) {
            for (read in listOf(true, false)) {
                val volumes = TestVolumes(listOf(p1, p2.copyOf(length), p3))
                val e = failure(volumes, read)
                assertTrue(e is RarCorruptException, "length $length, read $read: $e")
            }
        }
    }

    @Test
    fun `a continuation with another name, size, flag or encryption is corrupt`() {
        val (p1, p2, p3) = bytes(PLAIN)
        fun edited(edit: (ByteArray, RarBlocks.Raw) -> Unit): ByteArray {
            val copy = p2.copyOf()
            val block = RarBlocks.file(copy, "random.bin")
            edit(copy, block)
            RarBlocks.reseal(copy, block)
            return copy
        }
        val renamed = edited { bytes, block ->
            val at = RarBlocks.find(bytes, "random.bin".toByteArray(), block.start + 4, block.headerEnd)
            bytes[at + 9] = 'x'.code.toByte()
        }
        val resized = edited { bytes, block ->
            val at = RarBlocks.find(bytes, RarBuilder.vint(30000), block.start + 4, block.headerEnd)
            RarBuilder.vint(30001).copyInto(bytes, at)
        }
        val notContinued = edited { bytes, block ->
            // CRC (4), size (1), type (1), then the block flags.
            val at = block.start + 6
            assertEquals(HeaderFlags.SPLIT_BEFORE or HeaderFlags.EXTRA_DATA or HeaderFlags.DATA, bytes[at].toLong())
            bytes[at] = (HeaderFlags.EXTRA_DATA or HeaderFlags.DATA).toByte()
        }
        for ((label, second) in listOf("name" to renamed, "size" to resized, "split-before flag" to notContinued)) {
            for (read in listOf(true, false)) {
                val e = failure(TestVolumes(listOf(p1, second, p3)), read)
                assertTrue(e is RarCorruptException, "$label, read $read: $e")
                assertTrue(e.message!!.contains("Volume 1"), "$label: ${e.message}")
            }
        }

        val crypt = TestCrypt("secret")
        val otherIv = TestCrypt("secret", iv = ByteArray(16) { 7 })
        val data = ByteArray(100) { it.toByte() }
        val packed = crypt.encrypt(data)
        val halves = cut(packed, 48)
        val set = listOf(
            partVolume(0, 2, "e.bin", halves[0], 100, RarBuilder.crc(halves[0]), crypt.record()),
            partVolume(1, 2, "e.bin", halves[1], 100, RarBuilder.crc(data), otherIv.record()),
        )
        for (read in listOf(true, false)) {
            val volumes = TestVolumes(set)
            val e = failure(volumes, read) {
                RarOptions(passwordProvider = { "secret".toCharArray() }, volumeProvider = volumes, memoryBudget = it)
            }
            assertTrue(e is RarCorruptException, "encryption, read $read: $e")
            assertTrue(e.message!!.contains("Volume 1"), e.message)
        }
    }

    @Test
    fun `a stored entry split over three volumes reads as one`() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val volumes = TestVolumes(storedSet(data, cut(data, 300, 700)))
        RarReader(volumes.first(), RarOptions(volumeProvider = volumes)).use { reader ->
            val entry = reader.nextEntry()!!
            assertTrue(entry.splitAcrossVolumes)
            assertEquals(300L, entry.packedSize)
            assertEquals(1000L, entry.size)
            assertNull(entry.checksum)
            assertArrayEquals(data, reader.readCurrent())
            assertNull(reader.nextEntry())
        }
        assertEquals(listOf(0, 1, 2), volumes.opened)
        assertTrue(volumes.allClosed)
    }

    @Test
    fun `each part's checksum covers its packed bytes`() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val parts = cut(data, 300, 700)
        // Only the middle part's stored CRC is wrong; the file's own CRC still matches.
        val set = storedSet(data, parts, partCrc = { part -> RarBuilder.crc(part) + (if (part === parts[1]) 1 else 0) })
        val e = failure(TestVolumes(set), read = true)
        assertTrue(e is RarCorruptException, "$e")
        assertTrue(e.message!!.contains("part"), e.message)
        // Skipped parts are not verified.
        RarReader(ByteArrayInputStream(set[0]), RarOptions(volumeProvider = TestVolumes(set), listOnly = true)).use {
            assertEquals(listOf("s.bin"), Fixtures.listAll(it).map { entry -> entry.name })
        }

        fun blake(bytes: ByteArray) = Blake2sp().apply { update(bytes, 0, bytes.size) }.digest()
        fun hashed(firstDigest: ByteArray): List<ByteArray> {
            val halves = cut(data, 400)
            return listOf(
                partVolume(0, 2, "b.bin", halves[0], 1000, null, RarBuilder.record(0x02, RarBuilder.vint(0) + firstDigest)),
                partVolume(1, 2, "b.bin", halves[1], 1000, null, RarBuilder.record(0x02, RarBuilder.vint(0) + blake(data))),
            )
        }
        val good = TestVolumes(hashed(blake(data.copyOf(400))))
        RarReader(good.first(), RarOptions(volumeProvider = good)).use { reader ->
            assertNull(reader.nextEntry()!!.checksum)
            assertArrayEquals(data, reader.readCurrent())
        }
        val bad = failure(TestVolumes(hashed(blake(data))), read = true)
        assertTrue(bad is RarCorruptException && bad.message!!.contains("BLAKE2sp"), "$bad")
    }

    @Test
    fun `split stored data must add up to the entry's size`() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        val parts = cut(data, 300, 700)
        for (size in listOf(999L, 1001L)) {
            val set = parts.mapIndexed { i, part ->
                val crc = if (i == parts.lastIndex) RarBuilder.crc(data.copyOf(minOf(size, 1000L).toInt())) else RarBuilder.crc(part)
                partVolume(i, parts.size, "s.bin", part, size, crc)
            }
            val e = failure(TestVolumes(set), read = true)
            assertTrue(e is RarCorruptException, "size $size: $e")
        }
    }

    @Test
    fun `encrypted parts decrypt as one CBC chain`() {
        val crypt = TestCrypt("secret")
        val data = ByteArray(100) { (it * 3).toByte() }
        val packed = crypt.encrypt(data)
        // Part boundaries on and off AES block boundaries; 7-Zip decrypts the joined parts.
        for (cuts in listOf(intArrayOf(48), intArrayOf(37), intArrayOf(5, 90))) {
            val parts = cut(packed, *cuts)
            val set = parts.mapIndexed { i, part ->
                val crc = if (i == parts.lastIndex) RarBuilder.crc(data) else RarBuilder.crc(part)
                partVolume(i, parts.size, "e.bin", part, 100, crc, crypt.record())
            }
            val volumes = TestVolumes(set)
            RarReader(volumes.first(), RarOptions(passwordProvider = { "secret".toCharArray() }, volumeProvider = volumes)).use { reader ->
                assertTrue(reader.nextEntry()!!.isEncrypted)
                assertArrayEquals(data, reader.readCurrent(), cuts.joinToString())
            }
            assertTrue(volumes.allClosed)
        }

        // A joined total that is not whole AES blocks is corrupt.
        val short = cut(packed.copyOf(packed.size - 3), 48)
        val set = short.mapIndexed { i, part ->
            partVolume(i, 2, "e.bin", part, 100, if (i == 1) RarBuilder.crc(data) else RarBuilder.crc(part), crypt.record())
        }
        val volumes = TestVolumes(set)
        val e = failure(volumes, read = true) {
            RarOptions(passwordProvider = { "secret".toCharArray() }, volumeProvider = volumes, memoryBudget = it)
        }
        assertTrue(e is RarCorruptException, "$e")
    }

    @Test
    fun `provider exceptions propagate unchanged`() {
        val parts = bytes(PLAIN)
        for (read in listOf(true, false)) {
            val boom = IOException("volume unreadable")
            val volumes = TestVolumes { index -> if (index == 0) parts[0] else throw boom }
            assertSame(boom, failure(volumes, read))
            assertEquals(listOf(0, 1), volumes.opened)
        }
    }

    @Test
    fun `maxVolumes limits the volumes opened`() {
        for ((max, opened) in listOf(1 to listOf(0), 2 to listOf(0, 1))) {
            val volumes = TestVolumes(bytes(PLAIN))
            val e = failure(volumes, read = false) {
                RarOptions(volumeProvider = volumes, memoryBudget = it, limits = RarLimits(maxVolumes = max))
            }
            assertTrue(e is RarUnsupportedException, "$e")
            assertEquals(RarUnsupportedReason.LimitExceeded("maxVolumes", max + 1L, max.toLong()), (e as RarUnsupportedException).reason)
            assertEquals(opened, volumes.opened)
        }
        // A limit of 3 fits the set.
        val volumes = TestVolumes(bytes(PLAIN))
        RarReader(volumes.first(), RarOptions(volumeProvider = volumes, limits = RarLimits(maxVolumes = 3))).use {
            assertEquals(3, Fixtures.listAll(it).size)
        }
    }

    @Test
    fun `a later volume passed as the first is refused with its number`() {
        val parts = bytes(PLAIN)
        for (number in listOf(1, 2)) {
            val volumes = TestVolumes { index -> parts.getOrNull(index + number) }
            val e = assertThrows<RarUnsupportedException> { RarReader(volumes.first(), RarOptions(volumeProvider = volumes)) }
            assertEquals(RarUnsupportedReason.NotFirstVolume(number), e.reason)
            assertEquals(listOf(0), volumes.opened)
            assertTrue(volumes.allClosed)
        }
    }

    @Test
    fun `cancellation before a provider call propagates unchanged`() {
        val parts = bytes(PLAIN)
        // A first run counts the cancellation calls made up to the request for volume 1.
        var calls = 0
        var callsAtOpen = -1
        val probe = TestVolumes { index ->
            if (index == 1 && callsAtOpen < 0) callsAtOpen = calls
            parts.getOrNull(index)
        }
        RarReader(probe.first(), RarOptions(volumeProvider = probe, cancellation = { calls++ })).use { Fixtures.listAll(it) }
        assertTrue(callsAtOpen > 0)

        // Cancelling at the last of those calls stops before the provider is asked.
        val cancelled = Cancelled()
        var seen = 0
        val volumes = TestVolumes(parts)
        val e = failure(volumes, read = false) {
            RarOptions(volumeProvider = volumes, memoryBudget = it, cancellation = { if (++seen == callsAtOpen) throw cancelled })
        }
        assertSame(cancelled, e)
        assertEquals(listOf(0), volumes.opened)
    }
}

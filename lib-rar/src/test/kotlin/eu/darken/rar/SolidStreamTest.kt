package eu.darken.rar

import eu.darken.rar.RarBuilder.Companion.compression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException

class SolidStreamTest {
    /** compressed.rar's single stream: 1200 bytes of libarchive's verify_data pattern, method 5, 128 KiB window. */
    private val packed = RarBlocks.data(Fixtures.bytes("libarchive/test_read_format_rar5_compressed.rar"), "test.bin")

    /** An encryption record without a check value; no password is supplied, so the data is never decrypted. */
    private val crypt = RarBuilder.record(0x01, RarBuilder.vint(0) + RarBuilder.vint(0) + byteArrayOf(15) + ByteArray(32))

    private fun RarBuilder.testBin(name: String = "test.bin", solid: Boolean = false, main: Int = 0) =
        file(name, packed, compression = compression(method = 5, main = main, solid = solid), size = 1200, crc = 0x7CCA70CD)

    @Test
    fun `an encrypted predecessor without a password blocks the later solid entries`() {
        val path = "libarchive/test_read_format_rar5_solid_encrypted.rar"
        assertEquals(listOf("a.txt", "b.txt", "c.txt", "d.txt"), Fixtures.list(path).second.map { it.name })
        RarReader(Fixtures.stream(path), RarOptions(passwordProvider = null)).use { reader ->
            assertEquals("a.txt", reader.nextEntry()!!.name)
            val own = assertThrows<RarPasswordRequiredException> { reader.entryStream() }
            assertEquals(0, own.ordinal)
            for (name in listOf("b.txt", "c.txt", "d.txt")) {
                val entry = reader.nextEntry()!!
                assertEquals(name, entry.name)
                assertTrue(entry.isSolid)
                val e = assertThrows<RarPasswordRequiredException> { reader.entryStream() }
                assertEquals(RarPasswordScope.ENTRY, e.scope)
                assertEquals(0, e.ordinal)
                assertEquals("a.txt", e.entryName)
            }
            assertNull(reader.nextEntry())
        }
    }

    @Test
    fun `RAR 7_23 solid encrypted archive without a password names its first entry`() {
        val fixture = Fixtures.rar723.fixtures.getValue("solid-encrypted.rar")
        RarReader(Fixtures.stream("rar723/solid-encrypted.rar"), RarOptions(passwordProvider = null)).use { reader ->
            for (expected in fixture.entries) {
                val entry = reader.nextEntry()!!
                assertEquals(expected.name, entry.name)
                if (expected.type == "directory") {
                    assertEquals(0, reader.entryStream().readAllBytes().size)
                } else if (entry.ordinal > 0) {
                    val e = assertThrows<RarPasswordRequiredException> { reader.entryStream() }
                    assertEquals(0, e.ordinal, entry.name)
                    assertEquals(fixture.entries.first().name, e.entryName)
                }
            }
            assertNull(reader.nextEntry())
        }
    }

    @Test
    fun `the next non-solid entry starts over`() {
        val archive = RarBuilder().main(flags = 0x04)
            .file("locked.bin", ByteArray(32), compression = compression(method = 3), size = 20, crc = 0, extra = crypt)
            .file("follower.bin", ByteArray(16), compression = compression(method = 3, solid = true), size = 20, crc = 0, extra = crypt)
            .testBin()
            .testBin("again.bin", solid = true)
            .end().build()
        RarReader(ByteArrayInputStream(archive), RarOptions(passwordProvider = null)).use { reader ->
            reader.nextEntry()!!
            assertEquals("follower.bin", reader.nextEntry()!!.name)
            assertEquals(0, assertThrows<RarPasswordRequiredException> { reader.entryStream() }.ordinal)
            assertEquals("test.bin", reader.nextEntry()!!.name)
            assertTrue(DecodedDataTest.verifyData(reader.entryStream().readAllBytes(), 0))
            // The same stream repeated as a solid entry decodes again; the window state is valid.
            assertEquals("again.bin", reader.nextEntry()!!.name)
            assertTrue(DecodedDataTest.verifyData(reader.entryStream().readAllBytes(), 0))
            assertNull(reader.nextEntry())
        }
    }

    @Test
    fun `a solid entry without a stream before it is corrupt`() {
        val archive = RarBuilder().main(flags = 0x04).testBin(solid = true).end().build()
        RarReader(ByteArrayInputStream(archive)).use { reader ->
            reader.nextEntry()!!
            assertThrows<RarCorruptException> { reader.entryStream() }
            assertThrows<IOException> { reader.nextEntry() }
        }
        // Skipping it fails the same way, since a solid archive decodes what it skips.
        RarReader(ByteArrayInputStream(archive)).use { reader ->
            reader.nextEntry()!!
            assertThrows<RarCorruptException> { reader.nextEntry() }
        }
    }

    @Test
    fun `a solid entry with a different window is corrupt`() {
        val archive = RarBuilder().main(flags = 0x04).testBin().testBin("bigger.bin", solid = true, main = 1).end().build()
        RarReader(ByteArrayInputStream(archive)).use { reader ->
            reader.nextEntry()!!
            assertTrue(DecodedDataTest.verifyData(reader.entryStream().readAllBytes(), 0))
            reader.nextEntry()!!
            assertThrows<RarCorruptException> { reader.entryStream() }
        }
    }

    @Test
    fun `a non-solid archive skips without decoding`() {
        var calls = 0
        val archive = RarBuilder().main().testBin().testBin("second.bin").end().build()
        val listed = RarReader(ByteArrayInputStream(archive), RarOptions(cancellation = { calls++ })).use { Fixtures.listAll(it) }
        val skipCalls = calls
        calls = 0
        RarReader(ByteArrayInputStream(archive), RarOptions(cancellation = { calls++ })).use { reader ->
            reader.nextEntry()!!
            reader.entryStream().readAllBytes()
            reader.nextEntry()!!
            reader.entryStream().readAllBytes()
        }
        assertEquals(2, listed.size)
        // Decoding adds block and table work on top of the reads that skipping also does.
        assertTrue(calls > skipCalls, "$calls > $skipCalls")
        RarReader(ByteArrayInputStream(archive), RarOptions(memoryBudget = object : RarMemoryBudget {
            override fun acquire(bytes: Long) {
                throw AssertionError("Skipping a non-solid entry must not reserve a window")
            }

            override fun release(bytes: Long) = Unit
        })).use { Fixtures.listAll(it) }
    }
}

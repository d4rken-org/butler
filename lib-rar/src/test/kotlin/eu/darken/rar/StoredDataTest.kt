package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream

class StoredDataTest {
    companion object {
        /** Unencrypted single-volume fixtures with at least one whole stored file. */
        @JvmStatic
        fun fixtures(): List<String> = Fixtures.rar723.fixtures.values
            .filter { f -> !f.isVolumeSet && !f.headerEncrypted && f.entries.any { readable(it) } }
            .map { it.name }

        private fun readable(e: ManifestEntry) = e.type == "file" && e.stored && !e.encrypted && !e.splitAcrossVolumes && e.sha256 != null
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    fun `stored entries match the source files`(name: String) {
        val fixture = Fixtures.rar723.fixtures.getValue(name)
        var checked = 0
        RarReader(Fixtures.stream(Fixtures.rar723.path(fixture.files.single()))).use { reader ->
            for (expected in fixture.entries) {
                val entry = reader.nextEntry()!!
                assertEquals(expected.name, entry.name)
                if (!readable(expected)) continue
                val data = reader.entryStream().use { it.readAllBytes() }
                assertEquals(expected.size, data.size.toLong(), "$name ${entry.name}")
                assertEquals(expected.sha256, Fixtures.sha256(data), "$name ${entry.name}")
                checked++
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `redirections and directories yield empty streams`() {
        RarReader(Fixtures.stream("rar723/symlinks.rar")).use { reader ->
            while (true) {
                val entry = reader.nextEntry() ?: break
                if (entry.redirection != null || entry.isDirectory) {
                    assertEquals(0, reader.entryStream().readAllBytes().size, entry.name)
                }
            }
        }
        RarReader(Fixtures.stream("rar723/hardlinks.rar")).use { reader ->
            reader.nextEntry()
            assertEquals("hard linked content\n", String(reader.entryStream().readAllBytes()))
            val link = reader.nextEntry()!!
            assertEquals(RarRedirectionType.HARDLINK, link.redirection?.type)
            assertEquals(0, reader.entryStream().readAllBytes().size)
        }
    }

    @Test
    fun `corrupted stored data fails on the final read`() {
        val bytes = Fixtures.bytes("rar723/stored.rar")
        val content = "Hello, RAR!\n".toByteArray()
        val at = (0..bytes.size - content.size).first { i -> content.indices.all { bytes[i + it] == content[it] } }
        bytes[at + 3] = (bytes[at + 3].toInt() xor 0x20).toByte()
        RarReader(ByteArrayInputStream(bytes)).use { reader ->
            assertEquals("hello.txt", reader.nextEntry()!!.name)
            val stream = reader.entryStream()
            val head = ByteArray(content.size - 1)
            assertEquals(head.size, stream.readNBytes(head, 0, head.size))
            assertThrows<RarCorruptException> { stream.read() }
        }
    }

    @Test
    fun `stored entry whose packed size differs from its size is corrupt`() {
        val archive = RarBuilder().main().file("a.bin", "abcd".toByteArray(), size = 5).end().build()
        RarReader(ByteArrayInputStream(archive)).use { reader ->
            reader.nextEntry()
            assertThrows<RarCorruptException> { reader.entryStream() }
        }
    }

    @Test
    fun `stored entry of unknown size reads its packed data`() {
        val archive = RarBuilder().main().file("a.bin", "abcd".toByteArray(), size = null).end().build()
        RarReader(ByteArrayInputStream(archive)).use { reader ->
            assertEquals(null, reader.nextEntry()!!.size)
            assertEquals("abcd", String(reader.entryStream().readAllBytes()))
        }
    }

    @Test
    fun `BLAKE2sp entries are exposed with their digest`() {
        val (_, entries) = Fixtures.list("rar723/blake2.rar")
        assertTrue(entries.filter { !it.isDirectory }.all { it.checksum is RarChecksum.Blake2sp })
    }
}

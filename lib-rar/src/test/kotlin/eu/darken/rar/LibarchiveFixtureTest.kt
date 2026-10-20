package eu.darken.rar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class LibarchiveFixtureTest {
    companion object {
        /** Valid archives (first volumes for sets) whose listing must match RAR 7.23's. */
        private val LISTS = listOf(
            "arm", "bad_tables", "blake2", "compressed", "encrypted", "extra_field_version", "fileattr",
            "hardlink", "loop_bug", "main_block_extra_bytes", "multiarchive.part01", "multiarchive_solid.part01",
            "multiple_files", "multiple_files_solid", "only_crypt_exfld", "owner", "redir_varint_2byte",
            "skip_block_extra_bytes", "solid", "solid_encrypted", "stored", "stored_manyfiles", "symlink",
            "unicode", "unsupported_exfld", "win32", "zip_in_rar",
            // libarchive rejects its unknown hash type; RAR and this reader skip the record.
            "invalid_hash_valid_htime_exfld",
        ).map { "test_read_format_rar5_$it.rar" }

        @JvmStatic
        fun listed(): List<String> = LISTS

        /** `rar lt` lines that start an entry, over every volume of the set [name] starts. */
        private fun startingEntries(name: String): List<LibarchiveEntry> =
            Fixtures.volumeSet("libarchive/$name")
                .flatMap { Fixtures.libarchive.getValue(it.substringAfterLast('/')).entries }
                .filter { it.ratio != "<--" && it.ratio != "<->" }

        private fun options(name: String) = RarOptions(volumeProvider = TestVolumes.fixture("libarchive/$name"))
    }

    @ParameterizedTest
    @MethodSource("listed")
    fun `lists like RAR does`(name: String) {
        val fixture = Fixtures.libarchive.getValue(name)
        assertEquals(0, fixture.ltExitCode, name)
        val (_, entries) = Fixtures.list("libarchive/$name", options(name))
        val expected = startingEntries(name)
        assertEquals(expected.map { it.baseName }, entries.map { it.name }, name)
        expected.zip(entries).forEach { (e, a) ->
            val where = "$name ${e.name}"
            assertEquals(e.version, a.version, where)
            assertEquals(e.flags?.contains("encrypted") == true, a.isEncrypted, where)
            assertEquals(e.ltType == "Directory", a.isDirectory, where)
            if (e.ltType != "Directory") assertEquals(e.size, a.size, where)
            if (e.packedSize != null) assertEquals(e.packedSize, a.packedSize, where)
            when (e.ltType) {
                "Unix symbolic link" -> assertEquals(RarRedirectionType.UNIX_SYMLINK, a.redirection?.type, where)
                "Windows symbolic link" -> assertEquals(RarRedirectionType.WINDOWS_SYMLINK, a.redirection?.type, where)
                "Hard link" -> assertEquals(RarRedirectionType.HARDLINK, a.redirection?.type, where)
                else -> assertEquals(null, a.redirection, where)
            }
            e.target?.let { assertEquals(it, a.redirection?.target, where) }
            assertEquals(e.ratio == "-->", a.splitAcrossVolumes, where)
            if (a.splitAcrossVolumes) {
                assertEquals(null, a.checksum, where)
            } else if (e.checksum?.first() == "CRC32") {
                assertEquals(RarChecksum.Crc32(e.checksum!![1].toLong(16).toInt()), a.checksum, where)
            }
        }
    }

    @Test
    fun `header-encrypted fixtures need a password`() {
        for (name in listOf("encrypted_filenames", "solid_encrypted_filenames")) {
            val e = assertThrows<RarPasswordRequiredException> { RarReader(Fixtures.stream("libarchive/test_read_format_rar5_$name.rar"), RarOptions(passwordProvider = null)) }
            assertEquals(RarPasswordScope.HEADERS, e.scope)
        }
    }

    @Test
    fun `win32 fixture has Windows attributes and FILETIME times`() {
        val (_, entries) = Fixtures.list("libarchive/test_read_format_rar5_win32.rar")
        assertTrue(entries.all { it.hostOs == RarHostOs.WINDOWS })
        assertTrue(entries.all { it.mtime != null })
        val dir = entries.first { it.name == "testdir" }
        assertTrue(dir.isDirectory)
        assertTrue(dir.attributes and 0x10L != 0L)
    }

    @Test
    fun `stored content matches RAR's extraction`() {
        for (name in LISTS) {
            RarReader(Fixtures.stream("libarchive/$name"), options(name)).use { reader ->
                for (expected in startingEntries(name)) {
                    val entry = reader.nextEntry() ?: fail("$name ended before ${expected.name}")
                    val stored = expected.compression?.contains(" -m0") == true
                    val plain = expected.ltType == "File" && expected.flags?.contains("encrypted") != true
                    val whole = expected.ratio != "-->" && expected.size == expected.packedSize
                    if (!stored || !plain || !whole || expected.sha256 == null) continue
                    val data = reader.entryStream().readAllBytes()
                    assertEquals(expected.sha256, Fixtures.sha256(data), "$name ${expected.name}")
                }
            }
        }
    }
}

package eu.darken.rar

import eu.darken.rar.RarBuilder.Companion.compression
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.IOException

class EncryptionTest {
    private val rar723 = Fixtures.rar723

    private fun RarReader.readCurrent(): ByteArray = entryStream().use { it.readAllBytes() }

    private fun fixturePath(name: String) = rar723.path(rar723.fixtures.getValue(name).files.single())

    private fun where(fixture: String, entry: ManifestEntry) = "$fixture #${entry.ordinal} ${entry.name}"

    /** The stored checksum as `rar lt` shows it: the MAC itself for "CRC32 MAC". */
    private fun assertChecksum(expected: ManifestEntry, actual: RarEntry, where: String) {
        val (kind, value) = expected.ltChecksum ?: return
        if (kind == "CRC32" || kind == "CRC32 MAC") assertEquals(RarChecksum.Crc32(value.toLong(16).toInt()), actual.checksum, where)
    }

    /** Reads every entry of [name] to its end (which verifies its checksum or MAC) and compares it with the manifest. */
    private fun assertDecodes(name: String, provider: RarPasswordProvider, derivation: Rar5KeyDerivation = Rar5Crypto.DERIVATION) {
        val fixture = rar723.fixtures.getValue(name)
        cryptReader(Fixtures.stream(fixturePath(name)), RarOptions(passwordProvider = provider), derivation).use { reader ->
            assertEquals(fixture.headerEncrypted, reader.archive.headerEncrypted, name)
            for (expected in fixture.entries) {
                val at = where(name, expected)
                val entry = reader.nextEntry() ?: fail("$name ended before ${expected.name}")
                assertEquals(expected.ordinal, entry.ordinal, at)
                assertEquals(expected.name, entry.name, at)
                assertEquals(expected.encrypted, entry.isEncrypted, at)
                assertChecksum(expected, entry, at)
                val data = reader.readCurrent()
                if (expected.type == "file") {
                    assertEquals(expected.size, data.size.toLong(), at)
                    assertEquals(expected.sha256, Fixtures.sha256(data), at)
                }
            }
            assertNull(reader.nextEntry())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["encrypted-data.rar", "encrypted-stored.rar", "encrypted-unicode-password.rar", "solid-encrypted.rar"])
    fun `encrypted entries decode with their password`(name: String) {
        val provider = RecordingProvider(rar723.fixtures.getValue(name).listPassword)
        val derivation = CountingDerivation()
        assertDecodes(name, provider, derivation)
        // Every entry shares one salt, so the first entry's key serves them all.
        assertEquals(listOf(RarPasswordRequest(RarPasswordScope.ENTRY, 0, rar723.fixtures.getValue(name).entries.first().name)), provider.requests)
        assertEquals(1, derivation.calls)
        provider.assertWiped()
    }

    @Test
    fun `a wrong password is certain and asked again for every entry`() {
        val provider = RecordingProvider("passwore")
        val fixture = rar723.fixtures.getValue("encrypted-data.rar")
        cryptReader(Fixtures.stream(fixturePath("encrypted-data.rar")), RarOptions(passwordProvider = provider)).use { reader ->
            for (expected in fixture.entries) {
                val entry = reader.nextEntry()!!
                if (!entry.isEncrypted) {
                    assertEquals(0, reader.readCurrent().size)
                    continue
                }
                val e = assertThrows<RarWrongPasswordException>(entry.name) { reader.entryStream() }
                assertEquals(RarPasswordScope.ENTRY, e.scope)
                assertEquals(expected.ordinal, e.ordinal)
                assertEquals(expected.name, e.entryName)
                assertTrue(e.certain)
            }
            assertNull(reader.nextEntry())
        }
        assertEquals(fixture.entries.filter { it.encrypted }.map { it.ordinal }, provider.requests.map { it.ordinal })
        provider.assertWiped()
    }

    @Test
    fun `a non-ASCII password must match exactly`() {
        val provider = RecordingProvider("passwort密码")
        cryptReader(Fixtures.stream(fixturePath("encrypted-unicode-password.rar")), RarOptions(passwordProvider = provider)).use { reader ->
            reader.nextEntry()!!
            assertTrue(assertThrows<RarWrongPasswordException> { reader.entryStream() }.certain)
        }
        provider.assertWiped()
    }

    @Test
    fun `entries without a password fail without poisoning`() {
        for (provider in listOf(null, RecordingProvider(null as String?))) {
            cryptReader(Fixtures.stream(fixturePath("encrypted-stored.rar")), RarOptions(passwordProvider = provider)).use { reader ->
                for ((ordinal, name) in listOf(0 to "hello.txt", 1 to "numbers.txt")) {
                    assertEquals(name, reader.nextEntry()!!.name)
                    val e = assertThrows<RarPasswordRequiredException> { reader.entryStream() }
                    assertEquals(RarPasswordScope.ENTRY, e.scope)
                    assertEquals(ordinal, e.ordinal)
                    assertEquals(name, e.entryName)
                }
                assertNull(reader.nextEntry())
            }
            provider?.let { assertEquals(listOf(0, 1), it.requests.map { r -> r.ordinal }) }
        }
    }

    @Test
    fun `header-encrypted archive lists and decodes with its password`() {
        val fixture = rar723.fixtures.getValue("encrypted-headers.rar")
        val path = fixturePath("encrypted-headers.rar")

        val listing = RecordingProvider("password")
        val listed = cryptReader(Fixtures.stream(path), RarOptions(passwordProvider = listing, listOnly = true)).use { reader ->
            assertTrue(reader.archive.headerEncrypted)
            Fixtures.listAll(reader)
        }
        assertEquals(fixture.entries.map { it.name }, listed.map { it.name })
        assertEquals(fixture.entries.map { it.size }, listed.map { it.size })
        assertEquals(fixture.entries.map { it.encrypted }, listed.map { it.isEncrypted })
        assertEquals(listOf(RarPasswordRequest(RarPasswordScope.HEADERS, null, null)), listing.requests)
        listing.assertWiped()

        val reading = RecordingProvider("password")
        assertDecodes("encrypted-headers.rar", reading)
        assertEquals(RarPasswordRequest(RarPasswordScope.HEADERS, null, null), reading.requests.first())
        assertTrue(reading.requests.drop(1).isNotEmpty())
        assertTrue(reading.requests.drop(1).all { it.scope == RarPasswordScope.ENTRY })
        reading.assertWiped()
    }

    @Test
    fun `the header password is not reused for entries`() {
        val provider = RecordingProvider { if (it.scope == RarPasswordScope.HEADERS) "password" else null }
        cryptReader(Fixtures.stream(fixturePath("encrypted-headers.rar")), RarOptions(passwordProvider = provider)).use { reader ->
            assertEquals("hello.txt", reader.nextEntry()!!.name)
            val e = assertThrows<RarPasswordRequiredException> { reader.entryStream() }
            assertEquals(RarPasswordRequest(RarPasswordScope.ENTRY, 0, "hello.txt"), RarPasswordRequest(e.scope, e.ordinal, e.entryName))
            assertEquals("docs/lorem.txt", reader.nextEntry()!!.name)
        }
        assertEquals(listOf(RarPasswordScope.HEADERS, RarPasswordScope.ENTRY), provider.requests.map { it.scope })
    }

    @Test
    fun `header-encrypted archive refuses missing and wrong passwords`() {
        val path = fixturePath("encrypted-headers.rar")
        for (provider in listOf(null, RecordingProvider(null as String?))) {
            val stream = TrackingInputStream(Fixtures.stream(path))
            val e = assertThrows<RarPasswordRequiredException> { RarReader(stream, RarOptions(passwordProvider = provider)) }
            assertEquals(RarPasswordScope.HEADERS, e.scope)
            assertTrue(stream.closed)
        }

        val wrong = RecordingProvider("passwore")
        val stream = TrackingInputStream(Fixtures.stream(path))
        val e = assertThrows<RarWrongPasswordException> { RarReader(stream, RarOptions(passwordProvider = wrong)) }
        assertEquals(RarPasswordScope.HEADERS, e.scope)
        assertNull(e.ordinal)
        assertNull(e.entryName)
        assertTrue(e.certain)
        assertTrue(stream.closed)
        assertEquals(1, wrong.requests.size)
        wrong.assertWiped()
    }

    @Test
    fun `solid archive decodes skipped encrypted entries with their password`() {
        val fixture = rar723.fixtures.getValue("solid-encrypted.rar")
        val provider = RecordingProvider("password")
        cryptReader(Fixtures.stream(fixturePath("solid-encrypted.rar")), RarOptions(passwordProvider = provider)).use { reader ->
            // Entries 0 to 2 are skipped, which decodes them; entry 3 continues their stream.
            repeat(4) { reader.nextEntry()!! }
            val expected = fixture.entries[3]
            assertEquals("docs/lorem.txt", expected.name)
            assertEquals(expected.sha256, Fixtures.sha256(reader.readCurrent()))
        }
        assertEquals(listOf(RarPasswordRequest(RarPasswordScope.ENTRY, 0, fixture.entries[0].name)), provider.requests)
    }

    @Test
    fun `a solid predecessor without its password blocks the stream`() {
        val fixture = rar723.fixtures.getValue("solid-encrypted.rar")
        val first = fixture.entries.first()
        for (answer in listOf(null, "passwore")) {
            for (readFirst in listOf(false, true)) {
                val label = "answer $answer, readFirst $readFirst"
                val provider = RecordingProvider { if (it.ordinal == 0) answer else "password" }
                cryptReader(Fixtures.stream(fixturePath("solid-encrypted.rar")), RarOptions(passwordProvider = provider)).use { reader ->
                    reader.nextEntry()!!
                    if (readFirst) assertBlocked(assertThrows<RarException>(label) { reader.entryStream() }, answer, first, label)
                    for (expected in fixture.entries.drop(1)) {
                        reader.nextEntry()!!
                        if (expected.type != "file") continue
                        assertBlocked(assertThrows<RarException>(label) { reader.entryStream() }, answer, first, "$label ${expected.name}")
                    }
                    assertNull(reader.nextEntry())
                }
                // Once refused, the entry is not asked for again while skipping it.
                assertEquals(listOf(0), provider.requests.map { it.ordinal }, label)
            }
        }
    }

    private fun assertBlocked(e: RarException, answer: String?, by: ManifestEntry, label: String) {
        if (answer == null) {
            val required = e as? RarPasswordRequiredException ?: fail("$label: expected password required, got $e")
            assertEquals(RarPasswordScope.ENTRY, required.scope, label)
            assertEquals(by.ordinal, required.ordinal, label)
            assertEquals(by.name, required.entryName, label)
        } else {
            val wrong = e as? RarWrongPasswordException ?: fail("$label: expected wrong password, got $e")
            assertEquals(by.ordinal, wrong.ordinal, label)
            assertEquals(by.name, wrong.entryName, label)
            assertTrue(wrong.certain, label)
        }
    }

    @Test
    fun `per-file passwords are asked by ordinal`() {
        val fixture = rar723.fixtures.getValue("per-file-passwords.rar")
        val byOrdinal = fixture.entries.associate { it.ordinal to it.password }
        val provider = RecordingProvider { byOrdinal.getValue(it.ordinal!!) }
        assertDecodes("per-file-passwords.rar", provider)
        // All entries share one salt. Cached keys are tried against each entry's check value first,
        // so only the first entry of each password asks.
        assertEquals(listOf(0 to "a.txt", 1 to "b.txt"), provider.requests.map { it.ordinal to it.entryName })
        val versions = fixture.entries.filter { it.name == "same.txt" }
        assertEquals(listOf("one", "two"), versions.map { it.password })
        assertFalse(versions[0].sha256 == versions[1].sha256)
    }

    @Test
    fun `same-name versions are told apart by ordinal`() {
        val fixture = rar723.fixtures.getValue("per-file-passwords.rar")
        val byOrdinal = fixture.entries.associate { it.ordinal to it.password }
        val provider = RecordingProvider { byOrdinal.getValue(it.ordinal!!) }
        val contents = mutableMapOf<Int, String>()
        cryptReader(Fixtures.stream(fixturePath("per-file-passwords.rar")), RarOptions(passwordProvider = provider)).use { reader ->
            while (true) {
                val entry = reader.nextEntry() ?: break
                if (entry.name == "same.txt") contents[entry.ordinal] = Fixtures.sha256(reader.readCurrent())
            }
        }
        assertEquals(fixture.entries.filter { it.name == "same.txt" }.associate { it.ordinal to it.sha256!! }, contents)
        assertEquals(
            listOf(RarPasswordRequest(RarPasswordScope.ENTRY, 3, "same.txt"), RarPasswordRequest(RarPasswordScope.ENTRY, 4, "same.txt")),
            provider.requests,
        )
    }

    @Test
    fun `a wrong password on one entry leaves later entries readable`() {
        val fixture = rar723.fixtures.getValue("per-file-passwords.rar")
        val answers = mapOf(0 to null, 1 to "three", 3 to "one", 4 to "two")
        val provider = RecordingProvider { answers.getValue(it.ordinal!!) }
        cryptReader(Fixtures.stream(fixturePath("per-file-passwords.rar")), RarOptions(passwordProvider = provider)).use { reader ->
            assertEquals("a.txt", reader.nextEntry()!!.name)
            assertEquals(0, assertThrows<RarPasswordRequiredException> { reader.entryStream() }.ordinal)
            assertEquals("b.txt", reader.nextEntry()!!.name)
            val wrong = assertThrows<RarWrongPasswordException> { reader.entryStream() }
            assertEquals(1, wrong.ordinal)
            assertTrue(wrong.certain)
            for (expected in fixture.entries.drop(2)) {
                assertEquals(expected.name, reader.nextEntry()!!.name)
                assertEquals(expected.sha256, Fixtures.sha256(reader.readCurrent()), where("per-file-passwords.rar", expected))
            }
            assertNull(reader.nextEntry())
        }
        provider.assertWiped()
    }

    @Test
    fun `libarchive encrypted fixtures decode with their passwords`() {
        val names = listOf("encrypted", "encrypted_filenames", "solid_encrypted", "solid_encrypted_filenames")
        for (name in names.map { "test_read_format_rar5_$it.rar" }) {
            val fixture = Fixtures.libarchive.getValue(name)
            val passwords = fixture.entries.associate { it.name to it.password }
            val provider = RecordingProvider { if (it.scope == RarPasswordScope.HEADERS) "password" else passwords.getValue(it.entryName!!) }
            cryptReader(Fixtures.stream("libarchive/$name"), RarOptions(passwordProvider = provider)).use { reader ->
                assertEquals(name.contains("filenames"), reader.archive.headerEncrypted, name)
                for (expected in fixture.entries) {
                    val entry = reader.nextEntry() ?: fail("$name ended before ${expected.name}")
                    assertEquals(expected.name, entry.name, name)
                    assertEquals(expected.password != null, entry.isEncrypted, "$name ${entry.name}")
                    val data = reader.readCurrent()
                    assertEquals(expected.sha256, Fixtures.sha256(data), "$name ${entry.name}")
                    assertEquals("This is from ${entry.name}", String(data, Charsets.US_ASCII), "$name ${entry.name}")
                }
                assertNull(reader.nextEntry())
            }
        }
        // d.txt of the non-solid archive uses "password2"; b.txt shares its salt with "password".
        val passwords = Fixtures.libarchive.getValue("test_read_format_rar5_encrypted.rar").entries.map { it.password }
        assertEquals(listOf(null, "password", null, "password2"), passwords)
    }

    @Test
    fun `unencrypted entries of a partly encrypted archive need no provider`() {
        RarReader(Fixtures.stream("libarchive/test_read_format_rar5_encrypted.rar")).use { reader ->
            for (name in listOf("a.txt", "b.txt", "c.txt", "d.txt")) {
                val entry = reader.nextEntry()!!
                assertEquals(name, entry.name)
                if (entry.isEncrypted) {
                    assertEquals(entry.ordinal, assertThrows<RarPasswordRequiredException> { reader.entryStream() }.ordinal)
                } else {
                    assertEquals("This is from $name", String(reader.readCurrent(), Charsets.US_ASCII))
                }
            }
            assertNull(reader.nextEntry())
        }
    }

    @Test
    fun `the provider's array is wiped after success and failure`() {
        val right = RecordingProvider("password")
        cryptReader(Fixtures.stream(fixturePath("encrypted-stored.rar")), RarOptions(passwordProvider = right)).use { reader ->
            reader.nextEntry()!!
            reader.entryStream()
            right.assertWiped()
        }
        val wrong = RecordingProvider("passwore")
        cryptReader(Fixtures.stream(fixturePath("encrypted-stored.rar")), RarOptions(passwordProvider = wrong)).use { reader ->
            reader.nextEntry()!!
            assertThrows<RarWrongPasswordException> { reader.entryStream() }
            wrong.assertWiped()
        }
        val headers = RecordingProvider("password")
        RarReader(Fixtures.stream(fixturePath("encrypted-headers.rar")), RarOptions(passwordProvider = headers)).close()
        headers.assertWiped()
    }

    @Test
    fun `keys are derived once per salt and count`() {
        val shared = TestCrypt("secret")
        val other = TestCrypt("secret", salt = ByteArray(16) { (0x80 + it).toByte() })
        val contents = (0 until 4).map { "content of file $it".toByteArray() }
        val builder = RarBuilder().main()
        contents.forEachIndexed { i, plain ->
            val crypt = if (i < 3) shared else other
            builder.file("f$i.txt", crypt.encrypt(plain), size = plain.size.toLong(), crc = RarBuilder.crc(plain), extra = crypt.record())
        }
        val archive = builder.end().build()
        val provider = RecordingProvider("secret")
        val derivation = CountingDerivation()
        cryptReader(ByteArrayInputStream(archive), RarOptions(passwordProvider = provider), derivation).use { reader ->
            for (plain in contents) {
                reader.nextEntry()!!
                assertArrayEquals(plain, reader.readCurrent())
            }
        }
        assertEquals(listOf(0, 3), provider.requests.map { it.ordinal })
        assertEquals(2, derivation.calls)
    }

    @Test
    fun `an iteration count above maxKdfLog2 is refused before asking`() {
        val bytes = Fixtures.bytes(fixturePath("encrypted-stored.rar"))
        val raw = RarBlocks.file(bytes, "hello.txt")
        val kdfAt = RarBlocks.find(bytes, RarBlocks.crypt(bytes, "hello.txt").salt!!, raw.start, raw.headerEnd) - 1
        assertEquals(15, bytes[kdfAt].toInt())
        bytes[kdfAt] = 25
        RarBlocks.reseal(bytes, raw)
        val provider = RecordingProvider("password")
        val derivation = CountingDerivation()
        cryptReader(ByteArrayInputStream(bytes), RarOptions(passwordProvider = provider), derivation).use { reader ->
            reader.nextEntry()!!
            val e = assertThrows<RarUnsupportedException> { reader.entryStream() }
            assertEquals(RarUnsupportedReason.LimitExceeded("maxKdfLog2", 25, 24), e.reason)
            assertTrue(provider.requests.isEmpty())
            assertEquals(0, derivation.calls)
            // Only that entry is affected.
            reader.nextEntry()!!
            assertEquals(rar723.fixtures.getValue("encrypted-stored.rar").entries[1].sha256, Fixtures.sha256(reader.readCurrent()))
        }
        assertEquals(1, derivation.calls)

        val headers = Fixtures.bytes(fixturePath("encrypted-headers.rar"))
        val crypt = RarBlocks.cryptHeader(headers)
        val headerKdfAt = RarBlocks.find(headers, CryptHeader.parse(RarBlocks.header(headers, crypt)).salt!!, crypt.start, crypt.headerEnd) - 1
        assertEquals(15, headers[headerKdfAt].toInt())
        headers[headerKdfAt] = 25
        RarBlocks.reseal(headers, crypt)
        val headerProvider = RecordingProvider("password")
        val headerDerivation = CountingDerivation()
        val e = assertThrows<RarUnsupportedException> {
            cryptReader(ByteArrayInputStream(headers), RarOptions(passwordProvider = headerProvider), headerDerivation)
        }
        assertEquals(RarUnsupportedReason.LimitExceeded("maxKdfLog2", 25, 24), e.reason)
        assertTrue(headerProvider.requests.isEmpty())
        assertEquals(0, headerDerivation.calls)

        // The limit is configurable: the fixture's own 2^15 fails a limit of 14.
        val limited = assertThrows<RarUnsupportedException> {
            RarReader(Fixtures.stream(fixturePath("encrypted-headers.rar")), RarOptions(passwordProvider = { "password".toCharArray() }, limits = RarLimits(maxKdfLog2 = 14)))
        }
        assertEquals(RarUnsupportedReason.LimitExceeded("maxKdfLog2", 15, 14), limited.reason)
    }

    @Test
    fun `cancellation during key derivation propagates unchanged`() {
        class Cancelled : RuntimeException()
        var armed = false
        var hookThrew: Cancelled? = null
        val provider = RecordingProvider {
            armed = true
            "password"
        }
        // Created where the hook runs, so its stack trace shows the caller.
        val options = RarOptions(passwordProvider = provider, cancellation = { if (armed) throw Cancelled().also { hookThrew = it } })
        cryptReader(Fixtures.stream(fixturePath("encrypted-stored.rar")), options).use { reader ->
            reader.nextEntry()!!
            val thrown = assertThrows<Cancelled> { reader.entryStream() }
            assertSame(hookThrew, thrown)
            assertTrue(thrown.stackTrace.any { it.className == Rar5Crypto::class.java.name }, "Not thrown from the key derivation")
            assertThrows<IOException> { reader.nextEntry() }
        }
        provider.assertWiped()

        armed = false
        hookThrew = null
        val stream = TrackingInputStream(Fixtures.stream(fixturePath("encrypted-headers.rar")))
        val thrown = assertThrows<Cancelled> { RarReader(stream, options) }
        assertSame(hookThrew, thrown)
        assertTrue(thrown.stackTrace.any { it.className == Rar5Crypto::class.java.name })
        assertTrue(stream.closed)
    }

    @Test
    fun `damaged encrypted data with a proven password is corrupt`() {
        for ((name, entry) in listOf("encrypted-stored.rar" to "numbers.txt", "encrypted-data.rar" to "docs/lorem.txt")) {
            val bytes = Fixtures.bytes(fixturePath(name))
            val raw = RarBlocks.file(bytes, entry)
            val at = (raw.headerEnd + raw.dataEnd) / 2
            bytes[at] = (bytes[at].toInt() xor 0x55).toByte()
            cryptReader(ByteArrayInputStream(bytes), RarOptions(passwordProvider = { "password".toCharArray() })).use { reader ->
                while (reader.nextEntry()!!.name != entry) Unit
                assertThrows<RarCorruptException>(name) { reader.readCurrent() }
                assertThrows<IOException>(name) { reader.nextEntry() }
            }
        }
    }

    @Test
    fun `without a check value a wrong password shows on the final read`() {
        val bytes = Fixtures.bytes(fixturePath("encrypted-stored.rar"))
        clearCheckFlag(bytes, "hello.txt")
        val expected = rar723.fixtures.getValue("encrypted-stored.rar").entries

        // The right password still reads, verified by the MAC alone.
        cryptReader(ByteArrayInputStream(bytes), RarOptions(passwordProvider = { "password".toCharArray() })).use { reader ->
            reader.nextEntry()!!
            assertEquals(expected[0].sha256, Fixtures.sha256(reader.readCurrent()))
        }

        val provider = RecordingProvider { if (it.ordinal == 0) "passwore" else "password" }
        cryptReader(ByteArrayInputStream(bytes), RarOptions(passwordProvider = provider)).use { reader ->
            reader.nextEntry()!!
            val stream = reader.entryStream()
            val e = assertThrows<RarWrongPasswordException> { stream.readAllBytes() }
            assertEquals(RarPasswordScope.ENTRY, e.scope)
            assertEquals(0, e.ordinal)
            assertEquals("hello.txt", e.entryName)
            assertFalse(e.certain)
            assertThrows<IOException> { stream.read() }
            // Not poisoned; the suspect key is dropped, so the next entry asks again.
            reader.nextEntry()!!
            assertEquals(expected[1].sha256, Fixtures.sha256(reader.readCurrent()))
        }
        assertEquals(listOf(0, 1), provider.requests.map { it.ordinal })
        provider.assertWiped()
    }

    @Test
    fun `without a check value a wrong password fails compressed data as uncertain`() {
        val bytes = Fixtures.bytes(fixturePath("encrypted-data.rar"))
        clearCheckFlag(bytes, "docs/lorem.txt")
        val expected = rar723.fixtures.getValue("encrypted-data.rar").entries

        // A key cached from an earlier entry with the same salt serves an entry without a check value.
        val right = RecordingProvider("password")
        cryptReader(ByteArrayInputStream(bytes), RarOptions(passwordProvider = right)).use { reader ->
            reader.nextEntry()!!
            reader.readCurrent()
            reader.nextEntry()!!
            assertEquals(expected[1].sha256, Fixtures.sha256(reader.readCurrent()))
        }
        assertEquals(1, right.requests.size)

        val provider = RecordingProvider { if (it.ordinal == 1) "passwore" else "password" }
        cryptReader(ByteArrayInputStream(bytes), RarOptions(passwordProvider = provider)).use { reader ->
            reader.nextEntry()!!
            assertEquals("docs/lorem.txt", reader.nextEntry()!!.name)
            val e = assertThrows<RarWrongPasswordException> { reader.readCurrent() }
            assertEquals(1, e.ordinal)
            assertFalse(e.certain)
            assertEquals("docs/numbers.txt", reader.nextEntry()!!.name)
            assertEquals(expected[2].sha256, Fixtures.sha256(reader.readCurrent()))
        }
        assertEquals(listOf(1, 2), provider.requests.map { it.ordinal })
    }

    @Test
    fun `an unknown filter type behind an unchecked password fails as an uncertain wrong password`() {
        val data = ByteArray(16) { ('A' + it).code.toByte() }
        val packed = LzBlock().filter(start = 0, length = 8, type = 4).literals(data).build()
        val crypt = TestCrypt("secret")
        for (check in listOf(true, false)) {
            val bytes = RarBuilder().main()
                .file("bad.bin", crypt.encrypt(packed), compression = compression(method = 3), size = 16, crc = RarBuilder.crc(data), extra = crypt.record(check))
                .end().build()
            RarReader(ByteArrayInputStream(bytes), RarOptions(passwordProvider = { "secret".toCharArray() })).use { reader ->
                reader.nextEntry()!!
                val e = assertThrows<RarException>("check $check") { reader.entryStream().readAllBytes() }
                if (check) {
                    assertEquals(RarUnsupportedReason.UnknownFilter(4), (e as RarUnsupportedException).reason)
                } else {
                    assertFalse((e as RarWrongPasswordException).certain)
                    assertTrue(e.suppressed.any { it is RarUnsupportedException }, "${e.suppressed.toList()}")
                }
                assertNull(reader.nextEntry())
            }
        }
    }

    @Test
    fun `without a header check value a wrong password fails the first header as uncertain`() {
        val bytes = Fixtures.bytes(fixturePath("encrypted-headers.rar"))
        val crypt = RarBlocks.cryptHeader(bytes)
        val flagsAt = RarBlocks.find(bytes, CryptHeader.parse(RarBlocks.header(bytes, crypt)).salt!!, crypt.start, crypt.headerEnd) - 2
        assertEquals(0x01, bytes[flagsAt].toInt())
        bytes[flagsAt] = 0
        RarBlocks.reseal(bytes, crypt)

        val e = assertThrows<RarWrongPasswordException> { RarReader(ByteArrayInputStream(bytes), RarOptions(passwordProvider = { "passwore".toCharArray() })) }
        assertEquals(RarPasswordScope.HEADERS, e.scope)
        assertFalse(e.certain)

        val names = RarReader(ByteArrayInputStream(bytes), RarOptions(passwordProvider = { "password".toCharArray() }, listOnly = true)).use {
            Fixtures.listAll(it).map { entry -> entry.name }
        }
        assertEquals(rar723.fixtures.getValue("encrypted-headers.rar").entries.map { it.name }, names)
    }

    /** Clears the check-value flag of [name]'s encryption record; the 12 check bytes stay as ignored record data. */
    private fun clearCheckFlag(bytes: ByteArray, name: String) {
        val raw = RarBlocks.file(bytes, name)
        val flagsAt = RarBlocks.find(bytes, RarBlocks.crypt(bytes, name).salt!!, raw.start, raw.headerEnd) - 2
        assertEquals(0x03, bytes[flagsAt].toInt())
        bytes[flagsAt] = 0x02
        RarBlocks.reseal(bytes, raw)
        assertNull(RarBlocks.crypt(bytes, name).check)
    }

    @Test
    fun `algorithms other than AES-256 are unsupported`() {
        val header = RarBuilder().block(type = HeaderType.CRYPT, fields = RarBuilder.vint(1) + RarBuilder.vint(0)).build()
        val e = assertThrows<RarUnsupportedException> { RarReader(ByteArrayInputStream(header), RarOptions(passwordProvider = { "x".toCharArray() })) }
        assertEquals(RarUnsupportedReason.UnsupportedEncryption(1), e.reason)

        val entry = RarBuilder().main()
            .file("future.bin", ByteArray(16), size = 16, extra = RarBuilder.record(0x01, RarBuilder.vint(1) + RarBuilder.vint(0)))
            .file("after.txt", "after".toByteArray())
            .end().build()
        val provider = RecordingProvider("x")
        RarReader(ByteArrayInputStream(entry), RarOptions(passwordProvider = provider)).use { reader ->
            assertTrue(reader.nextEntry()!!.isEncrypted)
            assertEquals(RarUnsupportedReason.UnsupportedEncryption(1), assertThrows<RarUnsupportedException> { reader.entryStream() }.reason)
            reader.nextEntry()!!
            assertEquals("after", String(reader.readCurrent()))
        }
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `encrypted packed data must be whole AES blocks`() {
        val crypt = TestCrypt("secret")
        val plain = "twenty bytes of text".toByteArray()
        val archive = RarBuilder().main()
            .file("odd.txt", crypt.encrypt(plain).copyOf(31), size = plain.size.toLong(), crc = RarBuilder.crc(plain), extra = crypt.record())
            .end().build()
        val provider = RecordingProvider("secret")
        RarReader(ByteArrayInputStream(archive), RarOptions(passwordProvider = provider)).use { reader ->
            reader.nextEntry()!!
            assertThrows<RarCorruptException> { reader.entryStream() }
        }
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `MAC flag keys BLAKE2sp digests too`() {
        val crypt = TestCrypt("secret")
        val plain = "hashed with BLAKE2sp".toByteArray()
        val digest = Blake2sp().apply { update(plain, 0, plain.size) }.digest()
        fun archive(stored: ByteArray) = RarBuilder().main()
            .file(
                "b2.txt", crypt.encrypt(plain), size = plain.size.toLong(), crc = null,
                extra = crypt.record(mac = true) + RarBuilder.record(0x02, RarBuilder.vint(0) + stored),
            )
            .end().build()

        val mac = Rar5Crypto.macDigest(crypt.keys.hashKey, digest)
        RarReader(ByteArrayInputStream(archive(mac)), RarOptions(passwordProvider = { "secret".toCharArray() })).use { reader ->
            assertEquals(RarChecksum.Blake2sp(mac), reader.nextEntry()!!.checksum)
            assertArrayEquals(plain, reader.readCurrent())
        }
        // The plain digest is not accepted in place of the MAC.
        RarReader(ByteArrayInputStream(archive(digest)), RarOptions(passwordProvider = { "secret".toCharArray() })).use { reader ->
            reader.nextEntry()!!
            assertThrows<RarCorruptException> { reader.readCurrent() }
        }
    }

    @Test
    fun `a wrong password on a solid entry blocks the rest of its stream`() {
        // compressed.rar's stream decodes again as a solid continuation; the middle copy is encrypted.
        val packed = RarBlocks.data(Fixtures.bytes("libarchive/test_read_format_rar5_compressed.rar"), "test.bin")
        val crypt = TestCrypt("secret")
        fun archive(check: Boolean) = RarBuilder().main(flags = 0x04)
            .file("test.bin", packed, compression = compression(method = 5), size = 1200, crc = 0x7CCA70CD)
            .file("locked.bin", crypt.encrypt(packed), compression = compression(method = 5, solid = true), size = 1200, crc = 0x7CCA70CD, extra = crypt.record(check))
            .file("again.bin", packed, compression = compression(method = 5, solid = true), size = 1200, crc = 0x7CCA70CD)
            .end().build()

        // The right password: read, or skipped and decoded.
        for (readLocked in listOf(true, false)) {
            val provider = RecordingProvider("secret")
            RarReader(ByteArrayInputStream(archive(check = true)), RarOptions(passwordProvider = provider)).use { reader ->
                reader.nextEntry()!!
                assertTrue(DecodedDataTest.verifyData(reader.readCurrent(), 0))
                reader.nextEntry()!!
                if (readLocked) assertTrue(DecodedDataTest.verifyData(reader.readCurrent(), 0))
                reader.nextEntry()!!
                assertTrue(DecodedDataTest.verifyData(reader.readCurrent(), 0))
            }
            assertEquals(listOf(RarPasswordRequest(RarPasswordScope.ENTRY, 1, "locked.bin")), provider.requests)
        }

        for (check in listOf(true, false)) {
            val provider = RecordingProvider("wrong")
            RarReader(ByteArrayInputStream(archive(check)), RarOptions(passwordProvider = provider)).use { reader ->
                reader.nextEntry()!!
                assertTrue(DecodedDataTest.verifyData(reader.readCurrent(), 0))
                reader.nextEntry()!!
                val own = if (check) {
                    assertThrows<RarWrongPasswordException> { reader.entryStream() }
                } else {
                    assertThrows<RarWrongPasswordException> { reader.readCurrent() }
                }
                assertEquals(1, own.ordinal)
                assertEquals(check, own.certain)
                assertEquals("again.bin", reader.nextEntry()!!.name)
                val blocked = assertThrows<RarWrongPasswordException> { reader.entryStream() }
                assertEquals(1, blocked.ordinal)
                assertEquals("locked.bin", blocked.entryName)
                assertEquals(check, blocked.certain)
                assertNull(reader.nextEntry())
            }
            assertEquals(1, provider.requests.size)
        }
    }
}

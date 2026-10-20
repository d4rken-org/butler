package eu.darken.rar

import eu.darken.rar.Fixtures.toHex
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Rar5CryptoTest {
    @Test
    fun `PBKDF2-HMAC-SHA256 known answers`() {
        // RFC 7914 section 11.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            Rar5Crypto.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 64).toHex(),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            Rar5Crypto.pbkdf2("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64).toHex(),
        )
    }

    @Test
    fun `an empty password derives like a single zero byte`() {
        // Computed with CPython's hashlib.pbkdf2_hmac("sha256", b"", b"salt", 3, 32); HMAC zero-pads keys.
        val expected = "5ddf839afa2d5fb4be56e1a0f48917617559bef61ec122bfca1c7f75ac8f401d"
        assertEquals(expected, Rar5Crypto.pbkdf2(ByteArray(0), "salt".toByteArray(), 3, 32).toHex())
        assertEquals(expected, Rar5Crypto.pbkdf2(ByteArray(1), "salt".toByteArray(), 3, 32).toHex())
    }

    @Test
    fun `the AES key is the PBKDF2 key of 2^n iterations`() {
        val salt = ByteArray(16) { (it * 7).toByte() }
        val keys = Rar5Crypto.deriveKeys("password".toByteArray(), salt, 5) {}
        assertArrayEquals(Rar5Crypto.pbkdf2("password".toByteArray(), salt, 32, 32), keys.aesKey)
        // The hash key continues the same chain for 16 more iterations.
        assertArrayEquals(Rar5Crypto.pbkdf2("password".toByteArray(), salt, 48, 32), keys.hashKey)
    }

    @Test
    fun `fixture check values accept the password and reject a one-character change`() {
        val stored = Fixtures.bytes("rar723/encrypted-stored.rar")
        val crypt = RarBlocks.crypt(stored, "hello.txt")
        assertEquals(15, crypt.kdfCount)
        val right = Rar5Crypto.deriveKeys(Rar5Crypto.passwordBytes("password".toCharArray()), crypt.salt!!, crypt.kdfCount) {}
        val wrong = Rar5Crypto.deriveKeys(Rar5Crypto.passwordBytes("passwore".toCharArray()), crypt.salt!!, crypt.kdfCount) {}
        assertEquals(Rar5PasswordCheck.MATCH, Rar5Crypto.check(right, crypt.check))
        assertEquals(Rar5PasswordCheck.MISMATCH, Rar5Crypto.check(wrong, crypt.check))
        // The stored CRC32 is the MAC of hello.txt's plain CRC32 (manifest crc32 79c6ea4c, rar lt "CRC32 MAC 271AF56B").
        assertEquals(0x271AF56B, Rar5Crypto.macCrc32(right.hashKey, 0x79C6EA4C))

        val unicode = Fixtures.bytes("rar723/encrypted-unicode-password.rar")
        val unicodeCrypt = RarBlocks.crypt(unicode, "hello.txt")
        val keys = Rar5Crypto.deriveKeys(Rar5Crypto.passwordBytes("pässwört密码".toCharArray()), unicodeCrypt.salt!!, unicodeCrypt.kdfCount) {}
        assertEquals(Rar5PasswordCheck.MATCH, Rar5Crypto.check(keys, unicodeCrypt.check))
    }

    @Test
    fun `a check field whose own checksum fails decides nothing`() {
        val crypt = RarBlocks.crypt(Fixtures.bytes("rar723/encrypted-stored.rar"), "hello.txt")
        val keys = Rar5Crypto.deriveKeys("passwore".toByteArray(), crypt.salt!!, crypt.kdfCount) {}
        val damaged = crypt.check!!.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertEquals(Rar5PasswordCheck.ABSENT, Rar5Crypto.check(keys, damaged))
        assertEquals(Rar5PasswordCheck.ABSENT, Rar5Crypto.check(keys, null))
    }

    @Test
    fun `passwords are UTF-8 of at most 127 UTF-16 units`() {
        assertEquals("pässwört密码".toByteArray(Charsets.UTF_8).toHex(), Rar5Crypto.passwordBytes("pässwört密码".toCharArray()).toHex())
        assertEquals(127, Rar5Crypto.passwordBytes(CharArray(200) { 'a' }).size)
        // The cap counts chars, not bytes: 126 'a' and 'ä' are kept (128 bytes), the final 'b' is cut.
        assertEquals(128, Rar5Crypto.passwordBytes((CharArray(126) { 'a' } + 'ä' + 'b')).size)
        assertEquals(0, Rar5Crypto.passwordBytes(CharArray(0)).size)
    }

    @Test
    fun `cancellation runs between iteration batches and stops the derivation`() {
        class Cancelled : RuntimeException()
        val cancelled = Cancelled()
        var calls = 0
        val thrown = assertThrows<Cancelled> {
            Rar5Crypto.deriveKeys("password".toByteArray(), ByteArray(16), 20) { if (++calls == 3) throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertEquals(3, calls)

        // 2^10 + 32 iterations: the hook runs before the first and after every 1024.
        var counted = 0
        Rar5Crypto.deriveKeys("password".toByteArray(), ByteArray(16), 10) { counted++ }
        assertEquals(2, counted)
    }
}

package eu.darken.smb

import com.hierynomus.ntlm.functions.NtlmV2Functions
import com.hierynomus.security.bc.BCSecurityProvider
import eu.darken.smb.protocol.Crypto
import java.util.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class CryptoTest {
    @Test
    fun `Kotlin NTLMv2 response keys agree with pinned SMBJ oracle`() {
        val oracle = NtlmV2Functions(Random(0), BCSecurityProvider())
        for ((user, domain, password) in
            listOf(Triple("User", "Domain", "Password"), Triple("butler", "", "päss日本語"))) {
            assertArrayEquals(
                oracle.NTOWFv2(password, user, domain),
                Crypto.responseKey(password.toCharArray(), user, domain),
            )
        }
    }

    @Test
    fun `SMB3 KDF preserves the terminated label before the SP800 separator`() {
        val key = ByteArray(16) { it.toByte() }
        val context = "SmbSign\u0000".toByteArray()
        val suffix =
            "SMB2AESCMAC\u0000".toByteArray() +
                byteArrayOf(0) +
                context +
                byteArrayOf(0, 0, 0, 0x80.toByte())
        val oracle = BCSecurityProvider().getDerivationFunction("KDF/Counter/HMACSHA256")
        oracle.init(
            com.hierynomus.security.jce.derivationfunction.CounterDerivationParameters(
                key,
                suffix,
                32,
            )
        )
        val expected = ByteArray(16)
        oracle.generateBytes(expected, 0, expected.size)
        assertArrayEquals(expected, Crypto.kdf(key, "SMB2AESCMAC", context))
    }

    @Test
    fun `AES CMAC agrees with RFC4493 empty message vector`() {
        assertEquals(
            "bb1d6929e95937287fa37d129b756746",
            Crypto.cmac(
                    "2b7e151628aed2a6abf7158809cf4f3c".hexToByteArray(),
                    ByteArray(0),
                )
                .toHexString(),
        )
    }

    @Test
    fun `AES GCM agrees with NIST empty message vector`() {
        assertEquals(
            "58e2fccefa7e3061367f1d57a4e7455a",
            Crypto.aead(
                    true,
                    true,
                    ByteArray(16),
                    ByteArray(12),
                    ByteArray(0),
                    ByteArray(0),
                )
                .toHexString(),
        )
    }

    @Test
    fun `CCM and GCM reject modifications to payload and associated header`() {
        for (gcm in listOf(false, true)) {
            val key = ByteArray(16) { it.toByte() }
            val nonce = ByteArray(if (gcm) 12 else 11)
            val aad = byteArrayOf(1, 2)
            val plain = "payload".encodeToByteArray()
            val encrypted = Crypto.aead(true, gcm, key, nonce, aad, plain)
            assertArrayEquals(plain, Crypto.aead(false, gcm, key, nonce, aad, encrypted))
            val damaged = encrypted.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertThrows(Exception::class.java) {
                Crypto.aead(false, gcm, key, nonce, aad, damaged)
            }
            assertThrows(Exception::class.java) {
                Crypto.aead(false, gcm, key, nonce, byteArrayOf(2, 1), encrypted)
            }
        }
    }
}

package eu.darken.rar

import eu.darken.rar.Fixtures.toHex
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Answers with [answer]'s password; records every request and every array it handed out. */
internal class RecordingProvider(private val answer: (RarPasswordRequest) -> String?) : RarPasswordProvider {
    constructor(password: String?) : this({ password })

    val requests = mutableListOf<RarPasswordRequest>()
    val handedOut = mutableListOf<CharArray>()

    override fun password(request: RarPasswordRequest): CharArray? {
        requests += request
        return answer(request)?.toCharArray()?.also { handedOut += it }
    }

    fun assertWiped() {
        assertTrue(handedOut.isNotEmpty(), "No password was handed out")
        handedOut.forEach { array -> assertTrue(array.all { it == '\u0000' }, "Password array was not wiped") }
    }
}

/** Counts derivations and delegates to the real one. */
internal class CountingDerivation : Rar5KeyDerivation {
    var calls = 0
        private set

    override fun derive(password: ByteArray, salt: ByteArray, log2Count: Int, cancellation: () -> Unit): Rar5Keys {
        calls++
        return Rar5Crypto.deriveKeys(password, salt, log2Count, cancellation)
    }
}

/** Derives each (password, salt, count) once per test run; sweeps open the same archive thousands of times. */
internal object MemoizedDerivation : Rar5KeyDerivation {
    private val memo = ConcurrentHashMap<String, Rar5Keys>()

    override fun derive(password: ByteArray, salt: ByteArray, log2Count: Int, cancellation: () -> Unit): Rar5Keys =
        memo.getOrPut("${password.toHex()}:${salt.toHex()}:$log2Count") {
            Rar5Crypto.deriveKeys(password, salt, log2Count, cancellation)
        }.copy() // The reader wipes what it gets.
}

internal fun cryptReader(
    stream: InputStream,
    options: RarOptions = RarOptions(),
    derivation: Rar5KeyDerivation = Rar5Crypto.DERIVATION,
) = RarReader(stream, options, Rar5Window.DEFAULT_CHUNK_SIZE, derivation)

/** Builds encrypted entries for crafted archives; kdfCount 0 keeps derivation to one PBKDF2 round. */
internal class TestCrypt(
    password: String,
    val salt: ByteArray = ByteArray(16) { it.toByte() },
    val iv: ByteArray = ByteArray(16) { (0x40 + it).toByte() },
    val kdfCount: Int = 0,
) {
    val keys = Rar5Crypto.deriveKeys(password.toByteArray(), salt, kdfCount) {}

    /** File encryption record; [check] stores the password check value, [mac] keys the checksums. */
    fun record(check: Boolean = true, mac: Boolean = false): ByteArray {
        val flags = (if (check) 0x01L else 0L) or (if (mac) 0x02L else 0L)
        var payload = RarBuilder.vint(0) + RarBuilder.vint(flags) + byteArrayOf(kdfCount.toByte()) + salt + iv
        if (check) payload += keys.check + MessageDigest.getInstance("SHA-256").digest(keys.check).copyOf(4)
        return RarBuilder.record(0x01, payload)
    }

    /** [data] zero-padded to whole AES blocks and encrypted with this record's key and IV. */
    fun encrypt(data: ByteArray): ByteArray {
        val padded = data.copyOf((data.size + 15) / 16 * 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(padded)
    }
}

/**
 * Passwords for libarchive fixture [name]: each entry's from the manifest (per libarchive's tests),
 * "password" for the headers and for entries the manifest lists without one.
 */
internal fun libarchivePasswords(name: String): RarPasswordProvider {
    val entries = Fixtures.libarchive[name]?.entries.orEmpty()
    return RarPasswordProvider { request ->
        val entry = if (request.scope == RarPasswordScope.ENTRY) entries.firstOrNull { it.baseName == request.entryName } else null
        (entry?.password ?: "password").toCharArray()
    }
}

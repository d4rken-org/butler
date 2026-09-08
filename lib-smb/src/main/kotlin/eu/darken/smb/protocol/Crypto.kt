package eu.darken.smb.protocol

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

internal object Crypto {
    fun hash512(vararg values: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run {
            values.forEach(::update)
            digest()
        }

    fun hmac(algorithm: String, key: ByteArray, vararg values: ByteArray): ByteArray =
        Mac.getInstance(algorithm).run {
            init(SecretKeySpec(key, algorithm))
            values.forEach(::update)
            doFinal()
        }

    fun responseKey(password: CharArray, username: String, domain: String): ByteArray {
        val encoded = Charsets.UTF_16LE.encode(CharBuffer.wrap(password))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        val hash = ByteArray(16)
        try {
            MD4Digest().apply {
                update(bytes, 0, bytes.size)
                doFinal(hash, 0)
            }
            return hmac(
                "HmacMD5",
                hash,
                (username.uppercase(java.util.Locale.ROOT) + domain).utf16(),
            )
        } finally {
            bytes.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
            hash.fill(0)
        }
    }

    fun kdf(key: ByteArray, label: String, context: ByteArray): ByteArray {
        val input =
            ByteBuffer.allocate(4 + label.length + 2 + context.size + 4)
                .putInt(1)
                .put(label.toByteArray(Charsets.US_ASCII))
                .put(0)
                .put(0)
                .put(context)
                .putInt(128)
                .array()
        return hmac("HmacSHA256", key, input).copyOf(16)
    }

    fun cmac(key: ByteArray, value: ByteArray): ByteArray =
        ByteArray(16).also { output ->
            CMac(AESEngine.newInstance()).apply {
                init(KeyParameter(key))
                update(value, 0, value.size)
                doFinal(output, 0)
            }
        }

    fun aead(
        encrypt: Boolean,
        gcm: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
    ): ByteArray {
        if (gcm)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce),
                )
                updateAAD(aad)
                doFinal(input)
            }
        val cipher = CCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val result = ByteArray(cipher.getOutputSize(input.size))
        val size = cipher.processBytes(input, 0, input.size, result, 0)
        val finalSize = cipher.doFinal(result, size)
        return result.copyOf(size + finalSize)
    }
}

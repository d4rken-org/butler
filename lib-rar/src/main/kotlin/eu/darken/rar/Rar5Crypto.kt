/*
 * Copyright (C) Igor Pavlov
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

// Implemented from the RARLAB technote; key derivation, password check value and checksum MAC
// conversion follow 7-Zip's CPP/7zip/Crypto/Rar5Aes.cpp (Copyright (C) Igor Pavlov, GNU LGPL 2.1
// or later).

package eu.darken.rar

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The AES-256 key, the checksum MAC key and the 8-byte password check value of one password, salt and count. */
internal class Rar5Keys(val aesKey: ByteArray, val hashKey: ByteArray, val check: ByteArray) {
    fun copy() = Rar5Keys(aesKey.copyOf(), hashKey.copyOf(), check.copyOf())

    fun wipe() {
        aesKey.fill(0)
        hashKey.fill(0)
        check.fill(0)
    }
}

internal fun interface Rar5KeyDerivation {
    fun derive(password: ByteArray, salt: ByteArray, log2Count: Int, cancellation: () -> Unit): Rar5Keys
}

internal enum class Rar5PasswordCheck { MATCH, MISMATCH, ABSENT }

internal object Rar5Crypto {
    const val BLOCK_SIZE = 16
    const val SALT_SIZE = 16

    /** Same cap as 7-Zip's Rar5Handler (kPasswordLen_MAX), in UTF-16 units. */
    const val MAX_PASSWORD_CHARS = 127

    /** The highest iteration count derived is 2^24, whatever the limits allow. */
    const val MAX_LOG2_COUNT = 24

    private const val DIGEST_SIZE = 32
    private const val CHECK_SIZE = 8
    private const val CHECK_SUM_SIZE = 4

    /** Further PBKDF2 rounds after the AES key for the hash key, and after that for the check value. */
    private const val EXTRA_ROUNDS = 16L
    private const val CANCELLATION_INTERVAL = 1024L

    val DERIVATION = Rar5KeyDerivation { password, salt, log2Count, cancellation -> deriveKeys(password, salt, log2Count, cancellation) }

    /** UTF-8 of the first [MAX_PASSWORD_CHARS] chars; unpaired surrogates become '?'. */
    fun passwordBytes(password: CharArray): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val encoded = encoder.encode(CharBuffer.wrap(password, 0, minOf(password.size, MAX_PASSWORD_CHARS)))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        return bytes
    }

    /**
     * PBKDF2-HMAC-SHA256 block 1 with 2^[log2Count] iterations is the AES key; 16 more iterations of
     * the same chain give the hash key, 16 after those the value folded into the password check.
     */
    fun deriveKeys(password: ByteArray, salt: ByteArray, log2Count: Int, cancellation: () -> Unit): Rar5Keys {
        require(log2Count in 0..MAX_LOG2_COUNT)
        val stages = longArrayOf((1L shl log2Count) - 1, EXTRA_ROUNDS, EXTRA_ROUNDS)
        val (key, hashKey, checkSource) = pbkdf2Block(hmac(password), salt, 1, stages, cancellation)
        val check = ByteArray(CHECK_SIZE) { i ->
            (checkSource[i].toInt() xor checkSource[i + 8].toInt() xor checkSource[i + 16].toInt() xor checkSource[i + 24].toInt()).toByte()
        }
        checkSource.fill(0)
        return Rar5Keys(key, hashKey, check)
    }

    /** Plain PBKDF2-HMAC-SHA256 (RFC 8018) over the same block function. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Long, length: Int, cancellation: () -> Unit = {}): ByteArray {
        require(iterations >= 1 && length >= 0)
        val mac = hmac(password)
        val out = ByteArray(length)
        var index = 1
        var pos = 0
        while (pos < length) {
            val block = pbkdf2Block(mac, salt, index++, longArrayOf(iterations - 1), cancellation).single()
            block.copyInto(out, pos, 0, minOf(DIGEST_SIZE, length - pos))
            block.fill(0)
            pos += DIGEST_SIZE
        }
        return out
    }

    /**
     * PBKDF2's F for block [index]: U1 = PRF(salt || index), then for each of [stages] that many
     * further rounds, with a copy of the running XOR taken after each stage.
     */
    private fun pbkdf2Block(mac: Mac, salt: ByteArray, index: Int, stages: LongArray, cancellation: () -> Unit): Array<ByteArray> {
        val u = ByteArray(DIGEST_SIZE)
        val sum = ByteArray(DIGEST_SIZE)
        val out = Array(stages.size) { ByteArray(0) }
        try {
            mac.update(salt)
            mac.update(byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(sum)
            var done = 0L
            for ((stage, rounds) in stages.withIndex()) {
                var left = rounds
                while (left > 0) {
                    if (done++ % CANCELLATION_INTERVAL == 0L) cancellation()
                    mac.update(u)
                    mac.doFinal(u, 0)
                    for (i in 0 until DIGEST_SIZE) sum[i] = (sum[i].toInt() xor u[i].toInt()).toByte()
                    left--
                }
                out[stage] = sum.copyOf()
            }
            return out
        } catch (t: Throwable) {
            out.forEach { it.fill(0) }
            throw t
        } finally {
            u.fill(0)
            sum.fill(0)
        }
    }

    /**
     * Compares [keys] with a stored 12-byte check field: 8 check bytes and the first 4 bytes of
     * their SHA-256. A field whose own checksum fails cannot decide anything and counts as absent.
     */
    fun check(keys: Rar5Keys, stored: ByteArray?): Rar5PasswordCheck {
        if (stored == null || stored.size != CHECK_SIZE + CHECK_SUM_SIZE) return Rar5PasswordCheck.ABSENT
        val value = stored.copyOfRange(0, CHECK_SIZE)
        val sum = MessageDigest.getInstance("SHA-256").digest(value)
        if (!(0 until CHECK_SUM_SIZE).all { sum[it] == stored[CHECK_SIZE + it] }) return Rar5PasswordCheck.ABSENT
        return if (MessageDigest.isEqual(value, keys.check)) Rar5PasswordCheck.MATCH else Rar5PasswordCheck.MISMATCH
    }

    /** The stored form of a CRC32 when the MAC flag is set: HMAC of its little-endian bytes, folded to 32 bits. */
    fun macCrc32(hashKey: ByteArray, crc: Int): Int {
        val digest = hmac(hashKey).doFinal(byteArrayOf(crc.toByte(), (crc ushr 8).toByte(), (crc ushr 16).toByte(), (crc ushr 24).toByte()))
        var folded = 0
        for (i in 0 until DIGEST_SIZE step 4) {
            folded = folded xor ((digest[i].toInt() and 0xFF) or
                ((digest[i + 1].toInt() and 0xFF) shl 8) or
                ((digest[i + 2].toInt() and 0xFF) shl 16) or
                ((digest[i + 3].toInt() and 0xFF) shl 24))
        }
        return folded
    }

    /** The stored form of a BLAKE2sp digest when the MAC flag is set. */
    fun macDigest(hashKey: ByteArray, digest: ByteArray): ByteArray = hmac(hashKey).doFinal(digest)

    private fun hmac(key: ByteArray): Mac {
        // HMAC zero-pads short keys, so an empty key equals a single zero byte; SecretKeySpec rejects empty keys.
        val material = if (key.isEmpty()) ByteArray(1) else key
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(material, "HmacSHA256")) }
    }
}

/** AES-256-CBC decryption of whole 16-byte blocks, in place, continuing the chain across calls. */
internal class Rar5Cbc(key: ByteArray, iv: ByteArray) {
    private val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun decrypt(buffer: ByteArray, offset: Int, length: Int) {
        require(length % Rar5Crypto.BLOCK_SIZE == 0)
        if (length == 0) return
        val n = cipher.update(buffer, offset, length, buffer, offset)
        check(n == length) { "AES-CBC returned $n of $length bytes" }
    }
}

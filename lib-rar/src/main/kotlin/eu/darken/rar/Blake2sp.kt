/*
   BLAKE2 reference source code package - reference C implementations

   Copyright 2012, Samuel Neves <sneves@dei.uc.pt>.  You may use this under the
   terms of the CC0, the OpenSSL Licence, or the Apache Public License 2.0, at
   your option.  The terms of these licenses can be found at:

   - CC0 1.0 Universal : http://creativecommons.org/publicdomain/zero/1.0
   - OpenSSL license   : https://www.openssl.org/source/license.html
   - Apache 2.0        : http://www.apache.org/licenses/LICENSE-2.0

   More information about the BLAKE2 hash function can be found at
   https://blake2.net.
*/

// Kotlin port of libarchive v3.8.10 libarchive/archive_blake2s_ref.c and
// libarchive/archive_blake2sp_ref.c (with archive_blake2.h and archive_blake2_impl.h),
// limited to unkeyed hashing.

package eu.darken.rar

/** BLAKE2s with an explicit parameter block; [Blake2s.digest] is the unkeyed 32-byte hash. */
internal class Blake2s(
    digestLength: Int = OUT_BYTES,
    fanout: Int = 1,
    depth: Int = 1,
    nodeOffset: Int = 0,
    nodeDepth: Int = 0,
    innerLength: Int = 0,
    private val outLength: Int = digestLength,
    private val lastNode: Boolean = false,
) {
    private val h = IntArray(8)
    private var t0 = 0
    private var t1 = 0
    private var f0 = 0
    private var f1 = 0
    private val buf = ByteArray(BLOCK_BYTES)
    private var bufLength = 0
    private val m = IntArray(16)
    private val v = IntArray(16)

    init {
        for (i in 0 until 8) h[i] = IV[i]
        // Parameter block words 0-3: digest length, key length (0), fanout, depth; leaf length (0);
        // node offset; XOF length (0), node depth, inner length. Salt and personalization stay zero.
        h[0] = h[0] xor (digestLength or (fanout shl 16) or (depth shl 24))
        h[2] = h[2] xor nodeOffset
        h[3] = h[3] xor ((nodeDepth shl 16) or (innerLength shl 24))
    }

    fun update(input: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        var inOffset = offset
        var inLength = length
        val left = bufLength
        val fill = BLOCK_BYTES - left
        if (inLength > fill) {
            bufLength = 0
            System.arraycopy(input, inOffset, buf, left, fill)
            incrementCounter(BLOCK_BYTES)
            compress(buf, 0)
            inOffset += fill
            inLength -= fill
            while (inLength > BLOCK_BYTES) {
                incrementCounter(BLOCK_BYTES)
                compress(input, inOffset)
                inOffset += BLOCK_BYTES
                inLength -= BLOCK_BYTES
            }
        }
        System.arraycopy(input, inOffset, buf, bufLength, inLength)
        bufLength += inLength
    }

    fun digest(): ByteArray {
        incrementCounter(bufLength)
        if (lastNode) f1 = -1
        f0 = -1
        buf.fill(0, bufLength, BLOCK_BYTES)
        compress(buf, 0)
        val out = ByteArray(OUT_BYTES)
        for (i in 0 until 8) {
            val word = h[i]
            out[i * 4] = word.toByte()
            out[i * 4 + 1] = (word ushr 8).toByte()
            out[i * 4 + 2] = (word ushr 16).toByte()
            out[i * 4 + 3] = (word ushr 24).toByte()
        }
        return out.copyOf(outLength)
    }

    private fun incrementCounter(increment: Int) {
        t0 += increment
        if (Integer.compareUnsigned(t0, increment) < 0) t1++
    }

    private fun compress(block: ByteArray, offset: Int) {
        for (i in 0 until 16) {
            val p = offset + i * 4
            m[i] = (block[p].toInt() and 0xFF) or
                ((block[p + 1].toInt() and 0xFF) shl 8) or
                ((block[p + 2].toInt() and 0xFF) shl 16) or
                ((block[p + 3].toInt() and 0xFF) shl 24)
        }
        for (i in 0 until 8) v[i] = h[i]
        v[8] = IV[0]
        v[9] = IV[1]
        v[10] = IV[2]
        v[11] = IV[3]
        v[12] = t0 xor IV[4]
        v[13] = t1 xor IV[5]
        v[14] = f0 xor IV[6]
        v[15] = f1 xor IV[7]
        for (r in 0 until 10) {
            val s = SIGMA[r]
            g(s, 0, 0, 4, 8, 12)
            g(s, 1, 1, 5, 9, 13)
            g(s, 2, 2, 6, 10, 14)
            g(s, 3, 3, 7, 11, 15)
            g(s, 4, 0, 5, 10, 15)
            g(s, 5, 1, 6, 11, 12)
            g(s, 6, 2, 7, 8, 13)
            g(s, 7, 3, 4, 9, 14)
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(s: IntArray, i: Int, a: Int, b: Int, c: Int, d: Int) {
        v[a] = v[a] + v[b] + m[s[2 * i]]
        v[d] = Integer.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 12)
        v[a] = v[a] + v[b] + m[s[2 * i + 1]]
        v[d] = Integer.rotateRight(v[d] xor v[a], 8)
        v[c] = v[c] + v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 7)
    }

    companion object {
        const val BLOCK_BYTES = 64
        const val OUT_BYTES = 32

        private val IV = intArrayOf(
            0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
            0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
        )

        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )
    }
}

/** BLAKE2sp: eight BLAKE2s leaves over interleaved 64-byte blocks and a root over their hashes. */
internal class Blake2sp {
    private val leaves = Array(PARALLELISM) { i ->
        Blake2s(
            digestLength = Blake2s.OUT_BYTES,
            fanout = PARALLELISM,
            depth = 2,
            nodeOffset = i,
            innerLength = Blake2s.OUT_BYTES,
            outLength = Blake2s.OUT_BYTES,
            lastNode = i == PARALLELISM - 1,
        )
    }
    private val root = Blake2s(
        digestLength = Blake2s.OUT_BYTES,
        fanout = PARALLELISM,
        depth = 2,
        nodeDepth = 1,
        innerLength = Blake2s.OUT_BYTES,
        lastNode = true,
    )
    private val buf = ByteArray(PARALLELISM * Blake2s.BLOCK_BYTES)
    private var bufLength = 0

    fun update(input: ByteArray, offset: Int, length: Int) {
        var inOffset = offset
        var inLength = length
        var left = bufLength
        val fill = buf.size - left
        if (left != 0 && inLength >= fill) {
            System.arraycopy(input, inOffset, buf, left, fill)
            for (i in 0 until PARALLELISM) leaves[i].update(buf, i * Blake2s.BLOCK_BYTES, Blake2s.BLOCK_BYTES)
            inOffset += fill
            inLength -= fill
            left = 0
        }
        for (i in 0 until PARALLELISM) {
            var leafLength = inLength
            var leafOffset = inOffset + i * Blake2s.BLOCK_BYTES
            while (leafLength >= buf.size) {
                leaves[i].update(input, leafOffset, Blake2s.BLOCK_BYTES)
                leafOffset += buf.size
                leafLength -= buf.size
            }
        }
        inOffset += inLength - inLength % buf.size
        inLength %= buf.size
        if (inLength > 0) System.arraycopy(input, inOffset, buf, left, inLength)
        bufLength = left + inLength
    }

    fun digest(): ByteArray {
        for (i in 0 until PARALLELISM) {
            if (bufLength > i * Blake2s.BLOCK_BYTES) {
                val left = minOf(bufLength - i * Blake2s.BLOCK_BYTES, Blake2s.BLOCK_BYTES)
                leaves[i].update(buf, i * Blake2s.BLOCK_BYTES, left)
            }
            val hash = leaves[i].digest()
            root.update(hash, 0, hash.size)
        }
        return root.digest()
    }

    private companion object {
        const val PARALLELISM = 8
    }
}

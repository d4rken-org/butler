package eu.darken.rar

import eu.darken.rar.Fixtures.toHex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

class Blake2spTest {
    private fun blake2s(data: ByteArray) = Blake2s().apply { update(data, 0, data.size) }.digest().toHex()

    private fun blake2sp(data: ByteArray, step: Int = data.size.coerceAtLeast(1)): String {
        val hash = Blake2sp()
        var at = 0
        while (at < data.size) {
            val n = minOf(step, data.size - at)
            hash.update(data, at, n)
            at += n
        }
        return hash.digest().toHex()
    }

    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `BLAKE2s-256 known answers`() {
        // RFC 7693 Appendix B.
        assertEquals("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982", blake2s("abc".toByteArray()))
        assertEquals("69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9", blake2s(ByteArray(0)))
    }

    @Test
    fun `BLAKE2sp known answers around leaf and buffer boundaries`() {
        // Computed with CPython's hashlib.blake2s in tree mode (fanout 8, depth 2, inner size 32,
        // node offsets 0-7, last node 7, root at node depth 1) over bytes i % 251.
        val expected = mapOf(
            1 to "a6b9eecc25227ad788c99d3f236debc8da408849e9a5178978727a81457f7239",
            63 to "1024c940be7341449b5010522b509f65bbdc1287b455c2bb7f72b2c92fd0d189",
            64 to "52603b6cbfad4966cb044cb267568385cf35f21e6c45cf30aed19832cb51e9f5",
            65 to "fff24d3cc729d395daf978b0157306cb495797e6c8dca1731d2f6f81b849baae",
            511 to "8e1e8ee1ffa0a01028fff3bff0ae9df2565a82e55a04e9541bb78b9c4778336f",
            512 to "8d9e357863298dd8364b7caf4234317f8a49f180d788b7abffb521925f1e1ff1",
            513 to "8a4bc3330497e681f15daf24fc496044a1c32bf0a837a210399e1ae4af7e92be",
            1000 to "611f1af6610cdaf674ec2c9178f6376ebe234ef50998a3be3f1fa698fb779274",
            4113 to "88825457c2ddc6fdda3de11d7ab27b68faf0b5f3224ba16e67c4952bf6b8e780",
        )
        for ((size, digest) in expected) {
            for (step in listOf(1, 7, 64, 100, 512, 513, size)) {
                assertEquals(digest, blake2sp(pattern(size), step), "size $size step $step")
            }
        }
    }

    @Test
    fun `chunking does not change the digest`() {
        val data = Random(7).nextBytes(20_000)
        val whole = blake2sp(data)
        val random = Random(11)
        repeat(20) {
            val hash = Blake2sp()
            var at = 0
            while (at < data.size) {
                val n = minOf(random.nextInt(0, 1500), data.size - at)
                hash.update(data, at, n)
                at += n
            }
            assertEquals(whole, hash.digest().toHex())
        }
    }

    @Test
    fun `BLAKE2sp matches the hashes RAR 7_23 stored with -htb`() {
        val fixture = Fixtures.rar723.fixtures.getValue("blake2.rar")
        // Empty input and the known stored content, independent of the reader.
        assertEquals(fixture.entries.single { it.name == "empty.txt" }.ltChecksum!![1], blake2sp(ByteArray(0)))
        assertEquals(fixture.entries.single { it.name == "hello.txt" }.ltChecksum!![1], blake2sp("Hello, RAR!\n".toByteArray()))

        var checked = 0
        RarReader(Fixtures.stream(Fixtures.rar723.path("blake2.rar"))).use { reader ->
            for (expected in fixture.entries) {
                reader.nextEntry()!!
                if (expected.type != "file") continue
                val data = reader.entryStream().use { it.readAllBytes() }
                // The content is authoritative through its SHA-256; the digest is then computed here.
                assertEquals(expected.sha256, Fixtures.sha256(data), expected.name)
                assertEquals(listOf("BLAKE2", blake2sp(data)), expected.ltChecksum, expected.name)
                checked++
            }
        }
        assertEquals(9, checked)
    }

    @Test
    fun `BLAKE2sp matches libarchive's blake2 fixture`() {
        val entry = Fixtures.libarchive.getValue("test_read_format_rar5_blake2.rar").entries.single()
        RarReader(Fixtures.stream("libarchive/test_read_format_rar5_blake2.rar")).use { reader ->
            reader.nextEntry()!!
            val data = reader.entryStream().use { it.readAllBytes() }
            assertEquals(814, data.size)
            assertEquals(entry.sha256, Fixtures.sha256(data))
            assertEquals(listOf("BLAKE2", blake2sp(data)), entry.checksum)
        }
    }
}

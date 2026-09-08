package eu.darken.smb

import eu.darken.smb.protocol.Wire
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class WireTest {
    @Test
    fun `untrusted lengths and offsets cannot escape packet bounds`() {
        val wire = Wire(ByteArray(64))
        for ((offset, length) in
            listOf(-1 to 1, 0 to -1, 63 to 2, Int.MAX_VALUE to 2, 1 to Int.MAX_VALUE)) {
            assertThrows(SmbException::class.java) { wire.bytes(offset, length) }
        }
        assertEquals(0, wire.bytes(64, 0).size)
        assertThrows(SmbException::class.java) { wire.i64(60) }
    }

    @Test
    fun `malformed UTF16 is rejected rather than silently renamed`() {
        assertThrows(SmbException::class.java) {
            Wire(byteArrayOf(0x00, 0xd8.toByte())).utf16(0, 2)
        }
        assertThrows(SmbException::class.java) { Wire(ByteArray(3)).utf16(0, 3) }
    }
}

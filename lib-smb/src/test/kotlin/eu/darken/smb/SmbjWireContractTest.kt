package eu.darken.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2FileId
import com.hierynomus.mssmb2.messages.SMB2ReadRequest
import com.hierynomus.ntlm.functions.NtlmV2Functions
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smb.SMBBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class SmbjWireContractTest {
    @Test
    fun `NTLMv2 response key matches published User Domain Password vector`() {
        val functions = NtlmV2Functions(Random(0), BCSecurityProvider())
        assertEquals(
            "0c868a403bfd7a93a3001ef22ef02e3f",
            functions.NTOWFv2("Password", "User", "Domain").toHexString(),
        )
    }

    @Test
    fun `read request uses 64 bit offsets and the requested buffer length`() {
        val fileId = SMB2FileId(ByteArray(8) { 0x11 }, ByteArray(8) { 0x22 })
        val packet =
            SMB2ReadRequest(SMB2Dialect.SMB_2_1, fileId, 7, 9, 3L * 1024 * 1024 * 1024, 32768)
        val buffer = SMBBuffer()
        packet.write(buffer)
        val bytes = buffer.compactData
        val wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("fe534d42", bytes.copyOfRange(0, 4).toHexString())
        assertEquals(64, wire.getShort(4).toInt())
        assertEquals(8, wire.getShort(12).toInt())
        assertEquals(9, wire.getInt(36))
        assertEquals(7L, wire.getLong(40))
        assertEquals(49, wire.getShort(64).toInt())
        assertEquals(32768, wire.getInt(68))
        assertEquals(3L * 1024 * 1024 * 1024, wire.getLong(72))
        assertArrayEquals(ByteArray(8) { 0x11 } + ByteArray(8) { 0x22 }, bytes.copyOfRange(80, 96))
    }
}

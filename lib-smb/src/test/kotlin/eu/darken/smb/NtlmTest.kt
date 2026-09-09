package eu.darken.smb

import eu.darken.smb.protocol.Ntlm
import eu.darken.smb.protocol.Packet
import eu.darken.smb.protocol.statusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class NtlmTest {
    @Test
    fun `truncated challenge and missing target information are rejected`() {
        val challenge =
            Packet()
                .bytes("NTLMSSP\u0000".toByteArray())
                .u32(2)
                .zeros(8)
                .u32(0x22888215)
                .zeros(16)
                .u16(0)
                .u16(0)
                .u32(48)
                .build()
        for (token in
            listOf(byteArrayOf(0xA1.toByte(), 0x82.toByte()), challenge.copyOf(20), challenge)) {
            val error =
                assertThrows(SmbException::class.java) {
                    Ntlm()
                        .authenticate(
                            token,
                            SmbCredentials.Password("test", charArrayOf()),
                            "server",
                        )
                }
            assertEquals(SmbException.Kind.PROTOCOL, error.kind)
        }
    }

    @Test
    fun `server status mapping preserves code and operation phase`() {
        val expected =
            mapOf(
                0xc000000fL to SmbException.Kind.MISSING,
                0xc0000034L to SmbException.Kind.MISSING,
                0xc000003aL to SmbException.Kind.MISSING,
                0xc0000225L to SmbException.Kind.MISSING,
                0xc0000035L to SmbException.Kind.ALREADY_EXISTS,
                0xc0000022L to SmbException.Kind.ACCESS_DENIED,
                0xc000006dL to SmbException.Kind.AUTHENTICATION,
                0xc0000071L to SmbException.Kind.AUTHENTICATION,
                0xc0000072L to SmbException.Kind.AUTHENTICATION,
                0xc000015bL to SmbException.Kind.AUTHENTICATION,
                0xc00000ccL to SmbException.Kind.SHARE_MISSING,
                0xc0000101L to SmbException.Kind.DIRECTORY_NOT_EMPTY,
                0xc0000103L to SmbException.Kind.NOT_DIRECTORY,
                0xc00000baL to SmbException.Kind.IS_DIRECTORY,
                0xc000007fL to SmbException.Kind.DISK_FULL,
                0xc0000043L to SmbException.Kind.SHARING_VIOLATION,
                0xc0000121L to SmbException.Kind.ACCESS_DENIED,
                0xc000020cL to SmbException.Kind.TRANSPORT,
                0xc000020dL to SmbException.Kind.TRANSPORT,
                0xc00000c9L to SmbException.Kind.TRANSPORT,
                0xc0000203L to SmbException.Kind.TRANSPORT,
                0xc000035cL to SmbException.Kind.TRANSPORT,
                0xc000026eL to SmbException.Kind.TRANSPORT,
                0xc0000033L to SmbException.Kind.OTHER,
            )
        expected.forEach { (code, kind) ->
            assertEquals(kind, statusException(code).kind)
            assertEquals(code, statusException(code).status)
        }
        assertEquals(SmbException.Kind.AUTHENTICATION, statusException(0xc0000022L, 1).kind)
        assertEquals(SmbException.Kind.SHARE_ACCESS_DENIED, statusException(0xc0000022L, 2).kind)
    }
}

package eu.darken.smb.protocol

import eu.darken.smb.SmbCredentials
import java.security.SecureRandom

internal class Ntlm(private val random: SecureRandom = SecureRandom()) {
    private val flags = 0x22888215L
    private val signature = "NTLMSSP\u0000".toByteArray(Charsets.US_ASCII)
    private val version = byteArrayOf(10, 0, 0x61, 0x4a, 0, 0, 0, 15)
    private val negotiate =
        Packet().bytes(signature).u32(1).u32(flags).zeros(16).bytes(version).build()

    fun firstToken(): ByteArray =
        der(
            0x60,
            der(6, "2b0601050502".hexToByteArray()) +
                der(
                    0xa0,
                    der(
                        0x30,
                        der(0xa0, der(0x30, der(6, "2b06010401823702020a".hexToByteArray()))) +
                            der(0xa2, der(4, negotiate)),
                    ),
                ),
        )

    data class Authentication(val token: ByteArray, val sessionKey: ByteArray)

    fun authenticate(token: ByteArray, credentials: SmbCredentials, host: String): Authentication {
        val challenge = responseToken(token)
        val input = Wire(challenge)
        protocolCheck(
            input.bytes(0, 8).contentEquals(signature) && input.u32(8) == 2L,
            "Invalid NTLM challenge",
        )
        val negotiated = input.u32(20) and flags
        protocolCheck(negotiated and 0x20080201L == 0x20080201L, "NTLMv2 security flags missing")
        val nonce = input.bytes(24, 8)
        val target = input.bytes(input.int32(44), input.u16(40))
        val targetInfo = Wire(target)
        val pairs = linkedMapOf<Int, ByteArray>()
        var cursor = 0
        while (true) {
            val id = targetInfo.u16(cursor)
            val length = targetInfo.u16(cursor + 2)
            cursor += 4
            if (id == 0) {
                protocolCheck(length == 0, "Invalid NTLM target terminator")
                break
            }
            protocolCheck(id !in pairs, "Duplicate NTLM target field")
            pairs[id] = targetInfo.bytes(cursor, length)
            cursor += length
        }
        protocolCheck(cursor == target.size, "Trailing NTLM target data")
        pairs[6]?.let { protocolCheck(it.size == 4, "Invalid NTLM flags") }
        pairs[7]?.let { protocolCheck(it.size == 8, "Invalid NTLM timestamp") }
        val mic = pairs[7] != null || pairs[6]?.let { Wire(it).u32(0) and 2L != 0L } == true
        if (mic) pairs[6] = Packet().u32((pairs[6]?.let { Wire(it).u32(0) } ?: 0L) or 2).build()
        pairs[9] = "cifs/$host".utf16()
        // Direct TCP has no TLS channel to bind.
        pairs[10] = ByteArray(16)
        val targetBytes =
            Packet()
                .apply {
                    pairs.forEach { (id, value) -> u16(id).u16(value.size).bytes(value) }
                    zeros(4)
                }
                .build()
        val user =
            when (credentials) {
                SmbCredentials.Guest -> "Guest"
                is SmbCredentials.Password -> credentials.username
            }
        val domain =
            when (credentials) {
                SmbCredentials.Guest -> ""
                is SmbCredentials.Password -> credentials.domain
            }
        val password = (credentials as? SmbCredentials.Password)?.password ?: charArrayOf()
        val responseKey = Crypto.responseKey(password, user, domain)
        val time =
            pairs[7]
                ?: Packet().i64((System.currentTimeMillis() + 11_644_473_600_000) * 10_000).build()
        val clientNonce = ByteArray(8).also(random::nextBytes)
        val blob =
            Packet()
                .u32(0x101)
                .zeros(4)
                .bytes(time)
                .bytes(clientNonce)
                .zeros(4)
                .bytes(targetBytes)
                .zeros(4)
                .build()
        val proof = Crypto.hmac("HmacMD5", responseKey, nonce, blob)
        val sessionKey = Crypto.hmac("HmacMD5", responseKey, proof)
        responseKey.fill(0)
        val ntResponse = proof + blob
        val lmResponse = ByteArray(24)
        val fields =
            listOf(lmResponse, ntResponse, domain.utf16(), user.utf16(), ByteArray(0), ByteArray(0))
        val headerSize = 72 + if (mic) 16 else 0
        val packet = Packet().bytes(signature).u32(3)
        var offset = headerSize
        for (field in fields) {
            packet.u16(field.size).u16(field.size).u32(offset.toLong())
            offset += field.size
        }
        packet.u32(negotiated).bytes(version)
        if (mic) packet.zeros(16)
        fields.forEach(packet::bytes)
        val authenticate = packet.build()
        if (mic)
            Crypto.hmac("HmacMD5", sessionKey, negotiate, challenge, authenticate)
                .copyInto(authenticate, 72)
        val signKey =
            java.security.MessageDigest.getInstance("MD5")
                .digest(
                    sessionKey +
                        "session key to client-to-server signing key magic constant\u0000"
                            .toByteArray()
                )
        val mechanisms = der(0x30, der(6, "2b06010401823702020a".hexToByteArray()))
        val mechChecksum = Crypto.hmac("HmacMD5", signKey, ByteArray(4), mechanisms).copyOf(8)
        signKey.fill(0)
        val mechMic = Packet().u32(1).bytes(mechChecksum).u32(0).build()
        return Authentication(
            der(0xa1, der(0x30, der(0xa2, der(4, authenticate)) + der(0xa3, der(4, mechMic)))),
            sessionKey,
        )
    }

    private fun responseToken(value: ByteArray): ByteArray {
        if (value.size >= 8 && value.copyOfRange(0, 8).contentEquals(signature)) return value
        var found: ByteArray? = null
        fun visit(bytes: ByteArray, depth: Int) {
            protocolCheck(depth <= 8, "SPNEGO nesting exceeds limit")
            val wire = Wire(bytes)
            var pos = 0
            while (pos < bytes.size) {
                val tag = wire.u8(pos++)
                val firstLength = wire.u8(pos++)
                val length =
                    if (firstLength < 128) firstLength
                    else {
                        val count = firstLength and 127
                        protocolCheck(count in 1..3, "Invalid SPNEGO length")
                        var size = 0
                        repeat(count) { size = (size shl 8) or wire.u8(pos++) }
                        size
                    }
                val content = wire.bytes(pos, length)
                pos += length
                if (
                    tag == 4 &&
                        content.size >= 8 &&
                        content.copyOfRange(0, 8).contentEquals(signature)
                ) {
                    protocolCheck(found == null, "Multiple NTLM response tokens")
                    found = content
                } else if (tag and 0x20 != 0) visit(content, depth + 1)
            }
        }
        visit(value, 0)
        return found
            ?: throw eu.darken.smb.SmbException(
                eu.darken.smb.SmbException.Kind.PROTOCOL,
                message = "NTLM response token missing",
            )
    }
}

private fun der(tag: Int, content: ByteArray): ByteArray {
    val length =
        when {
            content.size < 128 -> byteArrayOf(content.size.toByte())
            content.size <= 255 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            else ->
                byteArrayOf(0x82.toByte(), (content.size ushr 8).toByte(), content.size.toByte())
        }
    return byteArrayOf(tag.toByte()) + length + content
}

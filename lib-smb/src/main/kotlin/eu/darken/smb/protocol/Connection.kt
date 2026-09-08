package eu.darken.smb.protocol

import eu.darken.smb.SmbConfig
import eu.darken.smb.SmbCredentials
import eu.darken.smb.SmbDialect
import eu.darken.smb.SmbEndpoint
import eu.darken.smb.SmbException
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class Connection(private val endpoint: SmbEndpoint, private val config: SmbConfig) {
    private val socket = Socket()
    private val closed = AtomicBoolean(false)
    private val sendLock = ReentrantLock()
    private val creditAvailable = sendLock.newCondition()
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Pending(
        val command: Int,
        val future: CompletableFuture<ByteArray> = CompletableFuture(),
    ) {
        var asyncId: Long? = null
    }

    private var multiCredit = false
    private var maxTransact = 65536
    private val random = SecureRandom()
    private var messageId = 0L
    private var credits = 1
    private var sessionId = 0L
    var treeId: Long = 0
        private set

    lateinit var dialect: SmbDialect
        private set

    var maxRead: Int = 65536
        private set

    var maxWrite: Int = 65536
        private set

    private var preauth = ByteArray(64)
    private var signingKey: ByteArray? = null
    private var encryptKey: ByteArray? = null
    private var decryptKey: ByteArray? = null
    private var signingRequired = false
    private var encryptionSupported = false
    private var encryptionRequired = false
    private var gcm = false
    private var authenticated = false
    private var guestRequested = false
    private val noncePrefix = ByteArray(3).also(random::nextBytes)
    private var nonceCounter = 0L
    val negotiated: Boolean
        get() = ::dialect.isInitialized

    val connected: Boolean
        get() = socket.isConnected && !closed.get()

    suspend fun connect(credentials: SmbCredentials) = io {
        socket.tcpNoDelay = true
        socket.connect(
            InetSocketAddress(endpoint.host, endpoint.port),
            config.connectTimeout.inWholeMilliseconds.toInt(),
        )
        negotiate()
        authenticate(credentials)
        val name = "\\\\${endpoint.host}\\${endpoint.share}".utf16()
        val response =
            exchange(3, Packet().u16(9).u16(0).u16(72).u16(name.size).bytes(name).build())
        checkStatus(response, 2)
        val wire = Wire(response)
        protocolCheck(wire.u16(64) == 16 && wire.u8(66) == 1, "Not a disk share")
        treeId = wire.u32(36)
        if (wire.u32(68) and 0x8000 != 0L) {
            protocolCheck(
                encryptionSupported && encryptKey != null,
                "Share requires unsupported encryption",
            )
            encryptionRequired = true
        }
        readerScope.launch {
            try {
                while (connected) {
                    val framed = readFrame(Long.MAX_VALUE)
                    val response = if (framed[0] == 0xfd.toByte()) decrypt(framed) else framed
                    val wire = Wire(response)
                    val waiter = pending[wire.i64(24)]
                    protocolCheck(waiter != null, "Response has no outstanding request")
                    validateResponse(
                        response,
                        framed[0] == 0xfd.toByte(),
                        waiter!!.command,
                        wire.i64(24),
                    )
                    sendLock.withLock {
                        credits = (credits + wire.u16(14)).coerceAtMost(65535)
                        creditAvailable.signalAll()
                    }
                    if (wire.u32(8) == 0x103L) {
                        protocolCheck(wire.u32(16) and 2 != 0L, "Pending response has no async id")
                        val asyncId = wire.i64(32)
                        protocolCheck(
                            waiter.asyncId == null || waiter.asyncId == asyncId,
                            "SMB async id changed",
                        )
                        waiter.asyncId = asyncId
                        continue
                    }
                    waiter.asyncId?.let {
                        protocolCheck(
                            wire.u32(16) and 2 != 0L && wire.i64(32) == it,
                            "Mismatched final async response",
                        )
                    }
                    pending.remove(wire.i64(24))?.future?.complete(response)
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                wipeKeys()
            }
        }
    }

    suspend fun request(command: Int, body: ByteArray, allowed: Set<Long> = emptySet()): ByteArray {
        val response =
            withTimeoutOrNull(config.requestTimeout) {
                ioAwait { submit(command, body) }
            } ?: throw SmbException(SmbException.Kind.TRANSPORT, message = "SMB request timed out")
        checkStatus(response, allowed = allowed)
        return response
    }

    fun requestBlocking(command: Int, body: ByteArray, allowed: Set<Long> = emptySet()): ByteArray {
        try {
            return submit(command, body)
                .get(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .also { checkStatus(it, allowed = allowed) }
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            disconnect()
            Thread.currentThread().interrupt()
            throw SmbException(SmbException.Kind.TRANSPORT, cause = error)
        } catch (error: TimeoutException) {
            disconnect()
            throw SmbException(
                SmbException.Kind.TRANSPORT,
                message = "SMB request timed out",
                cause = error,
            )
        }
    }

    suspend fun <T> cancellable(block: () -> T): T = io(block)

    private suspend fun <T> ioAwait(block: () -> CompletableFuture<T>): T =
        try {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { disconnect() }
                    try {
                        block().whenComplete { value, error ->
                            if (error == null) continuation.resume(value)
                            else continuation.resumeWithException(error)
                        }
                    } catch (error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        } catch (error: CancellationException) {
            disconnect()
            throw error
        }

    private fun submit(command: Int, body: ByteArray): CompletableFuture<ByteArray> =
        sendLock.withLock {
            val payload =
                when (command) {
                    8,
                    9 -> Wire(body).int32(4)
                    14 -> Wire(body).int32(28)
                    16 -> Wire(body).int32(4)
                    else -> body.size
                }
            val canSplit = command == 8 || command == 9
            val requiredCredits =
                if (multiCredit && !canSplit) ((payload.coerceAtLeast(1) - 1) / 65536) + 1 else 1
            var remaining = config.requestTimeout.inWholeNanoseconds
            while (credits < requiredCredits && connected) {
                if (remaining <= 0) {
                    disconnect()
                    throw SmbException(
                        SmbException.Kind.TRANSPORT,
                        message = "Timed out waiting for SMB credits",
                    )
                }
                remaining = creditAvailable.awaitNanos(remaining)
            }
            if (!connected)
                throw SmbException(
                    SmbException.Kind.TRANSPORT,
                    message = "SMB connection is closed",
                )
            val count =
                if (canSplit && multiCredit)
                    minOf(payload.toLong(), credits.toLong() * 65536).toInt()
                else payload
            val requestBody =
                if (count == payload) body
                else {
                    body.copyOf(if (command == 9) 48 + count else body.size).also {
                        it.put32(4, count.toLong())
                    }
                }
            val charge = if (multiCredit) ((count.coerceAtLeast(1) - 1) / 65536) + 1 else 1
            protocolCheck(messageId <= Long.MAX_VALUE - charge, "SMB message id exhausted")
            val id = messageId
            messageId += charge
            credits -= charge
            val packet = header(command, requestBody, id, charge, (64 - credits).coerceIn(1, 64))
            val waiter = Pending(command)
            pending[id] = waiter
            val deadline =
                deadlines.schedule(
                    {
                        val failure =
                            SmbException(
                                SmbException.Kind.TRANSPORT,
                                message = "SMB request timed out",
                            )
                        // submit also performs a blocking socket write before callers can await.
                        if (waiter.future.completeExceptionally(failure)) fail(failure)
                    },
                    config.requestTimeout.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
            waiter.future.whenComplete { _, _ -> deadline.cancel(false) }
            try {
                val frame =
                    if (encryptionRequired) encrypt(packet)
                    else
                        packet.also {
                            if (signingKey != null) {
                                it.put32(16, 8)
                                sign(it).copyInto(it, 48)
                            }
                        }
                sendFrame(frame)
            } catch (error: Throwable) {
                fail(error)
            }
            waiter.future
        }

    private fun fail(error: Throwable) {
        val failure =
            if (error is SmbException) error
            else SmbException(SmbException.Kind.TRANSPORT, cause = error)
        closed.set(true)
        runCatching { socket.close() }
        pending.values.forEach { it.future.completeExceptionally(failure) }
        pending.clear()
        sendLock.withLock { creditAvailable.signalAll() }
        readerScope.cancel()
    }

    fun disconnect() {
        if (!closed.compareAndSet(false, true)) return
        fail(SmbException(SmbException.Kind.TRANSPORT, message = "SMB connection is closed"))
    }

    private fun wipeKeys() {
        signingKey?.fill(0)
        encryptKey?.fill(0)
        decryptKey?.fill(0)
        preauth.fill(0)
    }

    private suspend fun <T> io(block: () -> T): T =
        try {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { disconnect() }
                    try {
                        if (closed.get())
                            throw SmbException(
                                SmbException.Kind.TRANSPORT,
                                message = "SMB connection is closed",
                            )
                        continuation.resume(block())
                    } catch (error: Throwable) {
                        if (
                            error is IOException &&
                                (error !is SmbException ||
                                    error.kind == SmbException.Kind.PROTOCOL ||
                                    error.kind == SmbException.Kind.TRANSPORT)
                        )
                            disconnect()
                        val mapped =
                            if (error is IOException && error !is SmbException) {
                                SmbException(SmbException.Kind.TRANSPORT, cause = error)
                            } else error
                        continuation.resumeWithException(mapped)
                    } finally {
                        if (closed.get()) wipeKeys()
                    }
                }
            }
        } catch (error: CancellationException) {
            disconnect()
            throw error
        }

    private fun negotiate() {
        val has311 = SmbDialect.SMB_3_1_1 in config.dialects
        val body =
            Packet()
                .u16(36)
                .u16(config.dialects.size)
                .u16(if (config.requireSigning) 3 else 1)
                .u16(0)
                .u32(0x44)
                .bytes(ByteArray(16).also(random::nextBytes))
        val contextOffset = (64 + 36 + config.dialects.size * 2 + 7) and -8
        body.u32(if (has311) contextOffset.toLong() else 0).u16(if (has311) 2 else 0).u16(0)
        config.dialects.sortedBy { it.value }.forEach { body.u16(it.value) }
        if (has311) {
            body.align(8)
            // SHA-512 pre-authentication integrity, followed by AES-128-GCM/CCM cipher
            // capabilities.
            body
                .u16(1)
                .u16(38)
                .zeros(4)
                .u16(1)
                .u16(32)
                .u16(1)
                .bytes(ByteArray(32).also(random::nextBytes))
                .align(8)
            body.u16(2).u16(6).zeros(4).u16(2).u16(2).u16(1)
        }
        val response = exchange(0, body.build(), preauthenticate = true)
        if (Wire(response).u32(8) == 0xc00000bbL)
            throw SmbException(SmbException.Kind.UNSUPPORTED_DIALECT)
        checkStatus(response)
        val wire = Wire(response)
        protocolCheck(wire.u16(64) == 65, "Invalid negotiate response")
        dialect =
            SmbDialect.entries.firstOrNull { it.value == wire.u16(68) }
                ?: throw SmbException(SmbException.Kind.UNSUPPORTED_DIALECT)
        protocolCheck(dialect in config.dialects, "Server selected an unoffered SMB dialect")
        signingRequired = config.requireSigning || wire.u16(66) and 2 != 0
        encryptionSupported = dialect.value >= 0x300 && wire.u32(88) and 0x40 != 0L
        multiCredit = dialect != SmbDialect.SMB_2_0_2 && wire.u32(88) and 4 != 0L
        maxRead = wire.int32(96).coerceAtMost(if (multiCredit) 1_048_576 else 65536)
        maxWrite = wire.int32(100).coerceAtMost(if (multiCredit) 1_048_576 else 65536)
        maxTransact = wire.int32(92).coerceAtMost(1_048_576)
        protocolCheck(maxRead > 0 && maxWrite > 0, "Invalid negotiated I/O size")
        if (dialect == SmbDialect.SMB_3_1_1) {
            var offset = wire.int32(124)
            val count = wire.u16(70)
            protocolCheck(
                count in 1..16 && offset >= 128 && offset % 8 == 0,
                "Invalid negotiate contexts",
            )
            var sawPreauth = false
            var sawEncryption = false
            repeat(count) {
                val kind = wire.u16(offset)
                val length = wire.u16(offset + 2)
                val data = Wire(wire.bytes(offset + 8, length))
                when (kind) {
                    1 -> {
                        protocolCheck(
                            !sawPreauth && data.u16(0) == 1 && data.u16(4) == 1,
                            "Unsupported pre-authentication hash",
                        )
                        protocolCheck(
                            data.size == 6 + data.u16(2),
                            "Invalid pre-authentication context",
                        )
                        sawPreauth = true
                    }
                    2 -> {
                        protocolCheck(
                            !sawEncryption && data.u16(0) == 1 && data.size == 4,
                            "Invalid cipher selection",
                        )
                        protocolCheck(data.u16(2) in 1..2, "Unsupported encryption cipher")
                        gcm = data.u16(2) == 2
                        sawEncryption = true
                        encryptionSupported = true
                    }
                    8 ->
                        throw SmbException(
                            SmbException.Kind.PROTOCOL,
                            message = "Unsolicited signing algorithm selection",
                        )
                }
                offset = (offset + 8 + length + 7) and -8
            }
            protocolCheck(sawPreauth, "Missing pre-authentication negotiation")
        }
        if (config.requireEncryption && !encryptionSupported)
            throw SmbException(
                SmbException.Kind.UNSUPPORTED_DIALECT,
                message = "Server does not support encryption",
            )
    }

    private fun authenticate(credentials: SmbCredentials) {
        guestRequested = credentials == SmbCredentials.Guest
        val ntlm = Ntlm(random)
        fun body(token: ByteArray): ByteArray =
            Packet()
                .u16(25)
                .u8(0)
                .u8(if (signingRequired) 3 else 1)
                .u32(0x40)
                .u32(0)
                .u16(88)
                .u16(token.size)
                .i64(0)
                .bytes(token)
                .build()
        val challenge = exchange(1, body(ntlm.firstToken()), preauthenticate = true)
        val challengeWire = Wire(challenge)
        if (challengeWire.u32(8) != 0xc0000016L) {
            checkStatus(challenge, 1)
            protocolCheck(false, "NTLM challenge missing")
        }
        protocolCheck(challengeWire.u16(64) == 9, "Invalid session challenge")
        sessionId = challengeWire.i64(40)
        val auth =
            ntlm.authenticate(
                challengeWire.bytes(challengeWire.u16(68), challengeWire.u16(70)),
                credentials,
                endpoint.host,
            )
        try {
            val response =
                exchange(
                    1,
                    body(auth.token),
                    preauthenticate = true,
                    beforeReceive = {
                        if (dialect.value < 0x300) {
                            signingKey = auth.sessionKey.copyOf()
                        } else if (dialect == SmbDialect.SMB_3_1_1) {
                            signingKey = Crypto.kdf(auth.sessionKey, "SMBSigningKey", preauth)
                            encryptKey = Crypto.kdf(auth.sessionKey, "SMBC2SCipherKey", preauth)
                            decryptKey = Crypto.kdf(auth.sessionKey, "SMBS2CCipherKey", preauth)
                        } else {
                            signingKey =
                                Crypto.kdf(
                                    auth.sessionKey,
                                    "SMB2AESCMAC",
                                    "SmbSign\u0000".toByteArray(),
                                )
                            encryptKey =
                                Crypto.kdf(
                                    auth.sessionKey,
                                    "SMB2AESCCM",
                                    "ServerIn \u0000".toByteArray(),
                                )
                            decryptKey =
                                Crypto.kdf(
                                    auth.sessionKey,
                                    "SMB2AESCCM",
                                    "ServerOut\u0000".toByteArray(),
                                )
                        }
                    },
                )
            checkStatus(response, 1)
            val wire = Wire(response)
            protocolCheck(wire.u16(64) == 9, "Invalid session setup response")
            val guest = wire.u16(66) and 3 != 0
            if (guest && credentials != SmbCredentials.Guest)
                throw SmbException(
                    SmbException.Kind.AUTHENTICATION,
                    message = "Password authentication was downgraded to guest",
                )
            if (guest && (signingRequired || config.requireEncryption))
                throw SmbException(
                    SmbException.Kind.AUTHENTICATION,
                    message = "Guest sessions cannot meet the requested security policy",
                )
            if (guest) {
                signingKey?.fill(0)
                signingKey = null
                encryptKey?.fill(0)
                encryptKey = null
                decryptKey?.fill(0)
                decryptKey = null
            }
            encryptionRequired = config.requireEncryption || wire.u16(66) and 4 != 0
            protocolCheck(
                !encryptionRequired || (encryptionSupported && !guest),
                "Session encryption unavailable",
            )
            authenticated = true
        } finally {
            auth.sessionKey.fill(0)
            auth.token.fill(0)
        }
    }

    private fun exchange(
        command: Int,
        body: ByteArray,
        preauthenticate: Boolean = false,
        beforeReceive: () -> Unit = {},
    ): ByteArray {
        protocolCheck(
            credits > 0 && messageId < Long.MAX_VALUE,
            "SMB credit or message id exhausted",
        )
        val id = messageId++
        credits--
        val packet =
            Packet()
                .bytes(byteArrayOf(0xfe.toByte(), 0x53, 0x4d, 0x42))
                .u16(64)
                .u16(if (!::dialect.isInitialized || dialect == SmbDialect.SMB_2_0_2) 0 else 1)
                .u32(0)
                .u16(command)
                .u16(64)
                .u32(0)
                .u32(0)
                .i64(id)
                .u32(0xfeff)
                .u32(treeId)
                .i64(sessionId)
                .zeros(16)
                .bytes(body)
                .build()
        if (authenticated && signingKey != null && !encryptionRequired) {
            packet.put32(16, 8)
            sign(packet).copyInto(packet, 48)
        }
        if (preauthenticate) preauth = Crypto.hash512(preauth, packet)
        val sent = if (authenticated && encryptionRequired) encrypt(packet) else packet
        val out = socket.getOutputStream()
        out.write(
            byteArrayOf(
                0,
                (sent.size ushr 16).toByte(),
                (sent.size ushr 8).toByte(),
                sent.size.toByte(),
            )
        )
        out.write(sent)
        out.flush()
        beforeReceive()
        val deadline = System.nanoTime() + config.requestTimeout.inWholeNanoseconds
        while (true) {
            val framed = readFrame(deadline)
            val encrypted = framed[0] == 0xfd.toByte()
            val response = if (encrypted) decrypt(framed) else framed
            val wire = Wire(response)
            validateResponse(response, encrypted, command, id)
            val pending = wire.u32(8) == 0x103L
            credits = (credits + wire.u16(14)).coerceAtMost(65535)
            if (pending) continue
            if (preauthenticate && !(command == 1 && wire.u32(8) == 0L))
                preauth = Crypto.hash512(preauth, response)
            return response
        }
    }

    private fun validateResponse(response: ByteArray, encrypted: Boolean, command: Int, id: Long) {
        val wire = Wire(response)
        protocolCheck(
            wire.size >= 64 &&
                wire.bytes(0, 4).contentEquals(byteArrayOf(0xfe.toByte(), 0x53, 0x4d, 0x42)),
            "Invalid SMB response signature",
        )
        protocolCheck(
            wire.u16(4) == 64 && wire.u16(12) == command && wire.i64(24) == id,
            "Mismatched SMB response",
        )
        protocolCheck(wire.u32(16) and 1 != 0L && wire.u32(20) == 0L, "Invalid SMB response flags")
        if (sessionId != 0L) protocolCheck(wire.i64(40) == sessionId, "Mismatched SMB session")
        if (authenticated && command != 3 && wire.u32(16) and 2 == 0L)
            protocolCheck(wire.u32(36) == treeId, "Mismatched SMB tree")
        val pending = wire.u32(8) == 0x103L
        if (authenticated && encryptionRequired)
            protocolCheck(encrypted, "Server returned an unencrypted response")
        val guestSetup = command == 1 && wire.u32(8) == 0L && wire.u16(66) and 3 != 0
        if (guestSetup && !guestRequested) throw SmbException(SmbException.Kind.AUTHENTICATION)
        if (!encrypted && wire.u32(16) and 8 != 0L && !guestSetup) {
            protocolCheck(signingKey != null, "Unexpected SMB signature")
            val signature = wire.bytes(48, 16)
            val unsigned = response.copyOf().also { it.fill(0, 48, 64) }
            protocolCheck(
                MessageDigest.isEqual(signature, sign(unsigned)),
                "SMB signature verification failed",
            )
        } else if (
            !encrypted &&
                signingKey != null &&
                !pending &&
                (authenticated || command == 1 && wire.u32(8) == 0L && !guestSetup)
        ) {
            protocolCheck(false, "Required SMB signature missing")
        }
        if (wire.u32(8) == 0L) {
            val expected =
                when (command) {
                    0 -> 65
                    1 -> 9
                    3 -> 16
                    5 -> 89
                    6 -> 60
                    7 -> 4
                    8,
                    9 -> 17
                    14,
                    16 -> 9
                    17 -> 2
                    else -> 0
                }
            protocolCheck(wire.u16(64) == expected, "Invalid SMB response structure")
            when (command) {
                5 -> wire.bytes(64, 88)
                6 -> wire.bytes(64, 60)
                8 -> {
                    protocolCheck(wire.u8(66) >= 80, "Invalid SMB data offset")
                    wire.bytes(wire.u8(66), wire.int32(68))
                }
                14,
                16 -> {
                    protocolCheck(wire.u16(66) >= 72, "Invalid SMB data offset")
                    wire.bytes(wire.u16(66), wire.int32(68))
                }
            }
        }
    }

    private fun header(
        command: Int,
        body: ByteArray,
        id: Long,
        charge: Int,
        request: Int,
    ): ByteArray =
        Packet()
            .bytes(byteArrayOf(0xfe.toByte(), 0x53, 0x4d, 0x42))
            .u16(64)
            .u16(if (!::dialect.isInitialized || dialect == SmbDialect.SMB_2_0_2) 0 else charge)
            .u32(0)
            .u16(command)
            .u16(request)
            .u32(0)
            .u32(0)
            .i64(id)
            .u32(0xfeff)
            .u32(treeId)
            .i64(sessionId)
            .zeros(16)
            .bytes(body)
            .build()

    private fun sendFrame(frame: ByteArray) {
        val out = socket.getOutputStream()
        out.write(
            byteArrayOf(
                0,
                (frame.size ushr 16).toByte(),
                (frame.size ushr 8).toByte(),
                frame.size.toByte(),
            )
        )
        out.write(frame)
        out.flush()
    }

    private fun sign(packet: ByteArray): ByteArray =
        if (!::dialect.isInitialized || dialect.value < 0x300) {
            Crypto.hmac("HmacSHA256", signingKey!!, packet).copyOf(16)
        } else Crypto.cmac(signingKey!!, packet)

    private fun encrypt(packet: ByteArray): ByteArray {
        protocolCheck(nonceCounter < Long.MAX_VALUE, "SMB encryption nonce exhausted")
        val nonce = (noncePrefix + Packet().i64(nonceCounter++).build()).copyOf(if (gcm) 12 else 11)
        val header =
            Packet()
                .bytes(byteArrayOf(0xfd.toByte(), 0x53, 0x4d, 0x42))
                .zeros(16)
                .bytes(nonce.copyOf(16))
                .u32(packet.size.toLong())
                .u16(0)
                .u16(1)
                .i64(sessionId)
                .build()
        val encrypted =
            Crypto.aead(true, gcm, encryptKey!!, nonce, header.copyOfRange(20, 52), packet)
        encrypted.copyOfRange(encrypted.size - 16, encrypted.size).copyInto(header, 4)
        return header + encrypted.copyOf(encrypted.size - 16)
    }

    private fun decrypt(packet: ByteArray): ByteArray {
        val wire = Wire(packet)
        protocolCheck(decryptKey != null && wire.size >= 52, "Unexpected encrypted SMB frame")
        protocolCheck(
            wire.u16(42) == 1 && wire.u16(40) == 0 && wire.i64(44) == sessionId,
            "Invalid SMB transform header",
        )
        val length = wire.int32(36)
        protocolCheck(length == wire.size - 52 && length >= 64, "Invalid encrypted SMB frame size")
        return try {
            Crypto.aead(
                false,
                gcm,
                decryptKey!!,
                wire.bytes(20, if (gcm) 12 else 11),
                wire.bytes(20, 32),
                wire.bytes(52, length) + wire.bytes(4, 16),
            )
        } catch (error: Exception) {
            throw SmbException(
                SmbException.Kind.PROTOCOL,
                message = "SMB encryption verification failed",
                cause = error,
            )
        }
    }

    private fun readFrame(deadline: Long): ByteArray {
        val input = socket.getInputStream()
        fun read(length: Int): ByteArray =
            ByteArray(length).also { buffer ->
                var offset = 0
                while (offset < length) {
                    val remaining =
                        if (deadline == Long.MAX_VALUE) Long.MAX_VALUE
                        else (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0)
                        throw SocketTimeoutException("SMB request deadline exceeded")
                    socket.soTimeout =
                        if (deadline == Long.MAX_VALUE) 0
                        else remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    val count = input.read(buffer, offset, length - offset)
                    if (count < 0) throw EOFException("SMB server closed connection")
                    offset += count
                }
            }
        val header = Wire(read(4))
        protocolCheck(header.u8(0) == 0, "Unsupported SMB session frame")
        val length = (header.u8(1) shl 16) or (header.u8(2) shl 8) or header.u8(3)
        protocolCheck(
            length in
                64..(if (::dialect.isInitialized) maxOf(maxRead, maxTransact) + 4096
                    else 1_048_576),
            "SMB frame exceeds negotiated limits",
        )
        return read(length)
    }

    private fun checkStatus(response: ByteArray, phase: Int = 0, allowed: Set<Long> = emptySet()) {
        val status = Wire(response).u32(8)
        if (status != 0L && status !in allowed) throw statusException(status, phase)
    }

    companion object {
        private val deadlines =
            java.util.concurrent
                .ScheduledThreadPoolExecutor(1) { runnable ->
                    Thread(runnable, "smb-deadlines").apply { isDaemon = true }
                }
                .apply { removeOnCancelPolicy = true }
    }
}

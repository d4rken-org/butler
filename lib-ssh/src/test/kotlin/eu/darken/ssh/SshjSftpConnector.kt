package eu.darken.ssh

import java.io.CharArrayReader
import java.io.EOFException
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.PublicKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import net.schmizz.concurrent.Promise
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.PacketType
import net.schmizz.sshj.sftp.Request
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuth
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthNone
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

/**
 * SFTP over sshj, kept as a test oracle: the contract suites run against it and [MinaSftpConnector]
 * alike. sshj cannot back Butler on Android, where every ed25519 path fails without adding Bouncy
 * Castle to the global provider list. Blocking sshj calls run on [Dispatchers.IO].
 */
internal class SshjSftpConnector(private val config: SftpConfig = SftpConfig()) : SftpConnector {
    override suspend fun connect(
        endpoint: SftpEndpoint,
        credentials: SshCredentials,
        hostKeyPolicy: HostKeyPolicy,
    ): SftpSession {
        val sshjConfig = SshjCrypto.config
        val authentication = Authentication.of(credentials, sshjConfig)
        val session = SshjSession(SSHClient(sshjConfig), config)
        try {
            session.open(endpoint, PolicyVerifier(hostKeyPolicy), credentials.username, authentication)
        } catch (error: Throwable) {
            session.disconnect()
            throw error
        } finally {
            authentication.wipe()
        }
        return session
    }
}

/**
 * Crypto provider selection for sshj. sshj registers Bouncy Castle as a global JCA provider on first
 * use unless told not to; this keeps the JVM's installed providers untouched and lets sshj drop
 * algorithms those providers lack (e.g. chacha20-poly1305 without a Poly1305 MAC).
 */
private object SshjCrypto {
    val config: Config

    init {
        SecurityUtils.setRegisterBouncyCastle(false)
        config = DefaultConfig()
    }
}

private class Authentication(val methods: (UserAuth) -> Iterable<AuthMethod>, val wipe: () -> Unit) {
    companion object {
        fun of(credentials: SshCredentials, config: Config): Authentication =
            when (credentials) {
                is SshCredentials.Password -> {
                    val secret = credentials.password.copyOf()
                    Authentication({ passwordMethods(it, OneShotPasswordFinder(secret)) }) { secret.fill('\u0000') }
                }
                is SshCredentials.PrivateKey -> {
                    val method = AuthPublickey(loadKey(credentials.keyBytes, credentials.passphrase, config))
                    Authentication({ listOf(method) }) {}
                }
            }

        /**
         * Probes with `none` for the server's methods, then sends the password over exactly one: `password`
         * if offered, otherwise `keyboard-interactive`. sshj sends a method without checking whether the
         * server offers it.
         */
        private fun passwordMethods(userAuth: UserAuth, finder: PasswordFinder): Iterable<AuthMethod> =
            sequence {
                yield(AuthNone())
                val allowed = userAuth.allowedMethods
                when {
                    "password" in allowed -> yield(AuthPassword(finder))
                    "keyboard-interactive" in allowed ->
                        yield(AuthKeyboardInteractive(PasswordResponseProvider(finder)))
                }
            }.asIterable()

        private fun loadKey(keyBytes: ByteArray, passphrase: CharArray?, config: Config): KeyPairWrapper {
            val secret = passphrase?.copyOf() ?: CharArray(0)
            val finder = OneShotPasswordFinder(secret)
            var chars = CharArray(0)
            try {
                val decoded =
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(keyBytes))
                chars = CharArray(decoded.remaining()).also { decoded.get(it) }
                decoded.array().fill('\u0000')
                val format = KeyProviderUtil.detectKeyFileFormat(CharArrayReader(chars), false)
                val provider =
                    Factory.Named.Util.create(config.fileKeyProviderFactories, format.toString())
                        ?: throw SshException(SshException.Kind.KEY_FORMAT, message = "Unsupported key format")
                provider.init(CharArrayReader(chars), null, finder)
                return KeyPairWrapper(provider.public, provider.private)
            } catch (error: SshException) {
                throw error
            } catch (error: Exception) {
                val kind =
                    if (finder.requested) SshException.Kind.KEY_PASSPHRASE
                    else SshException.Kind.KEY_FORMAT
                throw SshException(kind, message = "Private key could not be loaded: $kind", cause = error)
            } finally {
                chars.fill('\u0000')
                secret.fill('\u0000')
            }
        }
    }
}

/** sshj may erase what it receives, so every request gets a fresh copy of [secret]. */
private class OneShotPasswordFinder(private val secret: CharArray) : PasswordFinder {
    @Volatile var requested: Boolean = false

    override fun reqPassword(resource: Resource<*>?): CharArray {
        requested = true
        return secret.copyOf()
    }

    override fun shouldRetry(resource: Resource<*>?): Boolean = false
}

private class PolicyVerifier(private val policy: HostKeyPolicy) : HostKeyVerifier {
    @Volatile var refusal: SshException? = null

    override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
        val presented = HostKey.fromBlob(Buffer.PlainBuffer().putPublicKey(key).compactData)
        val kind =
            when (policy) {
                is HostKeyPolicy.Pinned ->
                    if (presented == policy.expected) return true
                    else SshException.Kind.HOST_KEY_MISMATCH
                HostKeyPolicy.Unknown -> SshException.Kind.HOST_KEY_UNKNOWN
            }
        refusal =
            SshException(
                kind,
                presented,
                "Host key ${presented.sha256Fingerprint} refused: $kind",
            )
        return false
    }

    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> =
        (policy as? HostKeyPolicy.Pinned)?.let { listOf(it.expected.type) } ?: emptyList()
}

private class SshjSession(private val client: SSHClient, config: SftpConfig) : SftpSession {
    private val timeoutMs = config.requestTimeout.inWholeMilliseconds
    private val connectTimeoutMs = config.connectTimeout.inWholeMilliseconds.toInt()
    private val disconnected = AtomicBoolean(false)
    private lateinit var engine: SFTPEngine
    private lateinit var charset: Charset

    override val connected: Boolean
        get() = !disconnected.get() && client.isConnected

    suspend fun open(
        endpoint: SftpEndpoint,
        verifier: PolicyVerifier,
        username: String,
        authentication: Authentication,
    ) = io {
        client.connectTimeout = connectTimeoutMs
        client.transport.timeoutMs = timeoutMs.toInt()
        client.addHostKeyVerifier(verifier)
        try {
            client.connect(endpoint.host, endpoint.port)
            client.socket.tcpNoDelay = true
        } catch (error: Exception) {
            verifier.refusal?.let { throw it }
            throw failure(error)
        }
        try {
            client.auth(username, authentication.methods(client.userAuth))
        } catch (error: UserAuthException) {
            throw SshException(SshException.Kind.AUTHENTICATION, cause = error)
        } catch (error: Exception) {
            throw failure(error)
        }
        try {
            engine = SFTPEngine(client).init()
            engine.timeoutMs = timeoutMs.toInt()
            charset = engine.subsystem.remoteCharset
        } catch (error: Exception) {
            throw failure(error)
        }
    }

    override suspend fun canonicalize(path: String): SftpPath = op {
        require(path.none { it == '\u0000' })
        val resolved = engine.canonicalize(path)
        SftpPath.parseAbsolute(resolved)
            ?: throw SshException(SshException.Kind.PROTOCOL, message = "Server returned non-canonical path")
    }

    override suspend fun stat(path: SftpPath): SftpEntry = op { entry(path, engine.stat(path.wire)) }

    override suspend fun lstat(path: SftpPath): SftpEntry = op { entry(path, engine.lstat(path.wire)) }

    override fun list(path: SftpPath): Flow<SftpEntry> = flow {
        directory(path).use { directory ->
            while (true) {
                val batch = op { readDir(directory.handle) } ?: break
                for ((name, attributes) in batch) emit(entry(path.child(name), attributes))
            }
        }
    }

    override suspend fun mkdir(path: SftpPath) = op {
        try {
            engine.makeDir(path.wire)
        } catch (error: SFTPException) {
            if (error.isFailure && lstatOrNull(path.wire) != null) throw exists(error)
            throw error
        }
    }

    override suspend fun delete(path: SftpPath, recursive: Boolean) = op {
        val attributes = engine.lstat(path.wire)
        if (attributes.fileType == SftpFileType.DIRECTORY) {
            if (recursive) deleteChildren(path)
            removeDirectory(path)
        } else {
            engine.remove(path.wire)
        }
    }

    override suspend fun rename(source: SftpPath, destination: SftpPath) = op {
        if (lstatOrNull(destination.wire) != null) throw exists(null)
        try {
            engine.rename(source.wire, destination.wire, emptySet())
        } catch (error: SFTPException) {
            if (error.isFailure && lstatOrNull(destination.wire) != null) throw exists(error)
            throw error
        }
    }

    override suspend fun readLink(path: SftpPath): String = op { engine.readLink(path.wire) }

    override suspend fun symlink(link: SftpPath, target: String): Unit = op {
        require(target.isNotEmpty() && target.none { it == '\u0000' })
        // OpenSSH, and servers following it, expect the target before the link path.
        exec(
                engine
                    .newRequest(PacketType.SYMLINK)
                    .putString(target, charset)
                    .putString(link.wire, charset)
            )
            .ensureStatusPacketIsOK()
    }

    override suspend fun openFile(path: SftpPath, mode: SftpOpenMode): SftpFile = op {
        val flags =
            when (mode) {
                SftpOpenMode.READ -> setOf(OpenMode.READ)
                SftpOpenMode.READ_WRITE -> setOf(OpenMode.READ, OpenMode.WRITE, OpenMode.CREAT)
                SftpOpenMode.CREATE_NEW ->
                    setOf(OpenMode.READ, OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
                SftpOpenMode.OVERWRITE ->
                    setOf(OpenMode.READ, OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            }
        val handle =
            try {
                exec(
                        engine
                            .newRequest(PacketType.OPEN)
                            .putString(path.wire, charset)
                            .putUInt32(OpenMode.toMask(flags).toLong())
                            .putFileAttributes(FileAttributes.EMPTY)
                    )
                    .ensurePacketTypeIs(PacketType.HANDLE)
                    .readBytes()
            } catch (error: SFTPException) {
                if (error.isFailure) {
                    if (mode == SftpOpenMode.CREATE_NEW && lstatOrNull(path.wire) != null) {
                        throw exists(error)
                    }
                    if (statOrNull(path.wire)?.fileType == SftpFileType.DIRECTORY) {
                        throw SshException(SshException.Kind.IS_DIRECTORY, cause = error)
                    }
                }
                throw error
            }
        val type =
            try {
                fstat(handle).fileType
            } catch (error: Exception) {
                runCatching { closeHandle(handle) }
                throw error
            }
        if (type == SftpFileType.DIRECTORY) {
            runCatching { closeHandle(handle) }
            throw SshException(SshException.Kind.IS_DIRECTORY)
        }
        SshjFile(handle)
    }

    override suspend fun setModifiedAt(path: SftpPath, instant: Instant) = op {
        require(instant.epochSeconds in 0..0xFFFF_FFFFL) { "Out of SFTP v3 time range" }
        val current = engine.stat(path.wire)
        val accessed =
            if (current.has(FileAttributes.Flag.ACMODTIME)) current.atime else instant.epochSeconds
        engine.setAttributes(
            path.wire,
            FileAttributes.Builder().withAtimeMtime(accessed, instant.epochSeconds).build(),
        )
    }

    override suspend fun setPermissions(path: SftpPath, mode: Int) = op {
        require(mode in 0..0x0FFF) { "Mode must be within 07777" }
        engine.setAttributes(path.wire, FileAttributes.Builder().withPermissions(mode).build())
    }

    override suspend fun capacity(path: SftpPath): SftpCapacity? = op {
        if (!engine.supportsServerExtension("statvfs", "openssh.com")) return@op null
        val reply =
            exec(engine.newExtendedRequest("statvfs@openssh.com").putString(path.wire, charset))
                .ensurePacketTypeIs(PacketType.EXTENDED_REPLY)
        val blockSize = reply.readUInt64()
        val fragmentSize = reply.readUInt64().takeIf { it > 0 } ?: blockSize
        val blocks = reply.readUInt64()
        reply.readUInt64() // f_bfree includes blocks reserved for root
        val available = reply.readUInt64()
        try {
            SftpCapacity(
                Math.multiplyExact(fragmentSize, blocks),
                Math.multiplyExact(fragmentSize, available),
            )
        } catch (error: ArithmeticException) {
            throw SshException(SshException.Kind.PROTOCOL, message = "Capacity overflow", cause = error)
        }
    }

    override fun disconnect() {
        if (!disconnected.compareAndSet(false, true)) return
        runCatching { client.socket?.close() }
        runCatching { client.disconnect() }
    }

    override suspend fun close() {
        if (disconnected.get()) return
        try {
            io {
                runCatching { engine.close() }
                client.disconnect()
            }
        } catch (_: Exception) {
        } finally {
            disconnect()
        }
    }

    private val SftpPath.wire: String
        get() = toString()

    private fun <T> blockingOp(block: () -> T): T {
        ensureOpen()
        return try {
            block()
        } catch (error: Throwable) {
            throw failure(error)
        }
    }

    private suspend fun <T> op(block: () -> T): T = io { blockingOp(block) }

    /** Cancelling the caller disconnects the session, which releases the blocked worker. */
    private suspend fun <T> io(block: () -> T): T {
        ensureOpen()
        val work = CompletableFuture.supplyAsync({ block() }, Dispatchers.IO.asExecutor())
        try {
            return work.await()
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) disconnect()
            throw error
        }
    }

    private fun ensureOpen() {
        // A dead transport never answers new requests, which would otherwise wait for the timeout.
        if (::engine.isInitialized && !client.isConnected) disconnect()
        if (disconnected.get()) {
            throw SshException(SshException.Kind.TRANSPORT, message = "Session is disconnected")
        }
    }

    private fun failure(error: Throwable): Throwable {
        if (error is SshException || error is IllegalArgumentException) return error
        if (error is Error) return error
        if (error.causes().any { it is TimeoutException }) {
            disconnect()
            return SshException(SshException.Kind.TRANSPORT, message = "Request timed out", cause = error)
        }
        if (error is SFTPException && error.statusCode != Response.StatusCode.UNKNOWN) {
            val kind = SshException.kindOfStatus(error.statusCode.code)
            return SshException(kind, message = "SFTP ${error.statusCode}: ${error.message}", cause = error)
        }
        val transportFailure =
            disconnected.get() ||
                error.causes().any {
                    it is TransportException ||
                        it is ConnectionException ||
                        it is SocketException ||
                        it is EOFException ||
                        it is java.net.UnknownHostException ||
                        it is java.net.SocketTimeoutException
                }
        if (error is TransportException && error.disconnectReason in protocolReasons) {
            return SshException(SshException.Kind.PROTOCOL, cause = error)
        }
        val kind =
            when {
                transportFailure -> SshException.Kind.TRANSPORT
                error is SSHException || error is Buffer.BufferException -> SshException.Kind.PROTOCOL
                error is java.io.IOException -> SshException.Kind.TRANSPORT
                else -> SshException.Kind.OTHER
            }
        return SshException(kind, message = "SSH operation failed: $kind (${error.message})", cause = error)
    }

    private fun exists(cause: Throwable?) =
        SshException(SshException.Kind.ALREADY_EXISTS, cause = cause)

    private fun exec(request: Request): Response =
        engine.request(request).retrieve(timeoutMs, TimeUnit.MILLISECONDS)

    private fun statOrNull(path: String): FileAttributes? =
        try {
            engine.stat(path)
        } catch (error: SFTPException) {
            if (error.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw error
        }

    private fun lstatOrNull(path: String): FileAttributes? =
        try {
            engine.lstat(path)
        } catch (error: SFTPException) {
            if (error.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw error
        }

    private fun fstat(handle: ByteArray): FileAttributes =
        exec(engine.newRequest(PacketType.FSTAT).putString(handle))
            .ensurePacketTypeIs(PacketType.ATTRS)
            .readFileAttributes()

    private fun closeHandle(handle: ByteArray) {
        exec(engine.newRequest(PacketType.CLOSE).putString(handle)).ensureStatusPacketIsOK()
    }

    private inner class Directory(val handle: ByteArray) : SftpResource {
        override suspend fun close() = op { closeHandle(handle) }
    }

    private suspend fun directory(path: SftpPath): Directory = op { Directory(openDir(path)) }

    private fun openDir(path: SftpPath): ByteArray =
        try {
            exec(engine.newRequest(PacketType.OPENDIR).putString(path.wire, charset))
                .ensurePacketTypeIs(PacketType.HANDLE)
                .readBytes()
        } catch (error: SFTPException) {
            val status = error.statusCode
            if (status == Response.StatusCode.NO_SUCH_FILE || status == Response.StatusCode.FAILURE) {
                val existing = statOrNull(path.wire)
                if (existing != null && existing.fileType != SftpFileType.DIRECTORY) {
                    throw SshException(SshException.Kind.NOT_DIRECTORY, cause = error)
                }
            }
            throw error
        }

    /** Returns `null` at the end of the directory. */
    private fun readDir(handle: ByteArray): List<Pair<String, FileAttributes>>? {
        val response = exec(engine.newRequest(PacketType.READDIR).putString(handle))
        return when (response.type) {
            PacketType.NAME -> {
                val count = response.readUInt32AsInt()
                val entries = ArrayList<Pair<String, FileAttributes>>(count)
                repeat(count) {
                    val name = response.readString(charset)
                    response.readStringAsBytes()
                    val attributes = response.readFileAttributes()
                    if (SftpPath.isValidSegment(name)) entries += name to attributes
                }
                entries
            }
            PacketType.STATUS -> {
                response.ensureStatusIs(Response.StatusCode.EOF)
                null
            }
            else -> throw SFTPException("Unexpected packet: ${response.type}")
        }
    }

    private fun listAll(path: SftpPath): List<Pair<String, FileAttributes>> {
        val handle = openDir(path)
        try {
            val entries = ArrayList<Pair<String, FileAttributes>>()
            while (true) entries += readDir(handle) ?: break
            return entries
        } finally {
            runCatching { closeHandle(handle) }
        }
    }

    private fun deleteChildren(path: SftpPath) {
        for ((name, attributes) in listAll(path)) {
            val child = path.child(name)
            val isDirectory =
                attributes.fileType == SftpFileType.DIRECTORY &&
                    engine.lstat(child.wire).fileType == SftpFileType.DIRECTORY
            if (isDirectory) {
                deleteChildren(child)
                removeDirectory(child)
            } else {
                engine.remove(child.wire)
            }
        }
    }

    private fun removeDirectory(path: SftpPath) {
        try {
            engine.removeDir(path.wire)
        } catch (error: SFTPException) {
            if (error.isFailure && hasChildren(path)) {
                throw SshException(SshException.Kind.DIRECTORY_NOT_EMPTY, cause = error)
            }
            throw error
        }
    }

    private fun hasChildren(path: SftpPath): Boolean {
        val handle = openDir(path)
        try {
            while (true) {
                val batch = readDir(handle) ?: return false
                if (batch.isNotEmpty()) return true
            }
        } finally {
            runCatching { closeHandle(handle) }
        }
    }

    private fun entry(path: SftpPath, attributes: FileAttributes): SftpEntry {
        val hasMode = attributes.has(FileAttributes.Flag.MODE)
        val hasOwner = attributes.has(FileAttributes.Flag.UIDGID)
        return SftpEntry(
            path = path,
            type = attributes.fileType,
            size = if (attributes.has(FileAttributes.Flag.SIZE)) attributes.size else 0,
            modifiedAt =
                Instant.fromEpochSeconds(
                    if (attributes.has(FileAttributes.Flag.ACMODTIME)) attributes.mtime else 0
                ),
            permissions = if (hasMode) attributes.mode.permissionsMask else null,
            uid = if (hasOwner) attributes.uid else null,
            gid = if (hasOwner) attributes.gid else null,
        )
    }

    private inner class SshjFile(private val handle: ByteArray) : SftpFile {
        private val closed = AtomicBoolean(false)
        private val pendingWrites = ArrayDeque<Promise<Response, SFTPException>>()

        private val blockingView =
            object : SftpBlockingFile {
                override fun read(offset: Long, buffer: ByteArray, start: Int, length: Int): Int =
                    blockingOp { readAt(offset, buffer, start, length) }

                override fun write(offset: Long, buffer: ByteArray, start: Int, length: Int) =
                    blockingOp { writeAt(offset, buffer, start, length) }

                override fun size(): Long = blockingOp { currentSize() }

                override fun resize(size: Long) = blockingOp { truncate(size) }

                override fun flush() = blockingOp { awaitWrites() }

                override fun close() {
                    if (releasedWithSession()) return
                    blockingOp { closeFile() }
                }
            }

        override fun blocking(): SftpBlockingFile = blockingView

        override suspend fun read(offset: Long, buffer: ByteArray, start: Int, length: Int): Int =
            op { readAt(offset, buffer, start, length) }

        override suspend fun write(offset: Long, buffer: ByteArray, start: Int, length: Int) =
            op { writeAt(offset, buffer, start, length) }

        override suspend fun size(): Long = op { currentSize() }

        override suspend fun resize(size: Long) = op { truncate(size) }

        override suspend fun flush() = op { awaitWrites() }

        override suspend fun close() {
            if (releasedWithSession()) return
            op { closeFile() }
        }

        /** Server handles die with the connection, so closing after a disconnect has nothing to do. */
        private fun releasedWithSession(): Boolean {
            if (disconnected.get()) closed.set(true)
            return closed.get()
        }

        private fun ensureFileOpen() {
            if (closed.get()) throw SshException(SshException.Kind.OTHER, message = "File is closed")
        }

        private fun checkRange(offset: Long, buffer: ByteArray, start: Int, length: Int) {
            require(offset >= 0 && start >= 0 && length >= 0 && start <= buffer.size - length)
            require(offset <= Long.MAX_VALUE - length)
        }

        private fun readAt(offset: Long, buffer: ByteArray, start: Int, length: Int): Int {
            ensureFileOpen()
            checkRange(offset, buffer, start, length)
            if (length == 0) return 0
            awaitWrites()
            val requests = ArrayDeque<Pair<Int, Promise<Response, SFTPException>>>()
            var requested = 0
            var received = 0
            var endOfFile = false
            var stopped = false
            var failure: Throwable? = null
            while (true) {
                while (!stopped && requested < length && requests.size < MAX_IN_FLIGHT) {
                    val size = minOf(CHUNK_SIZE, length - requested)
                    val request =
                        engine
                            .newRequest(PacketType.READ)
                            .putString(handle)
                            .putUInt64(offset + requested)
                            .putUInt32(size.toLong())
                    requests.addLast(size to engine.request(request))
                    requested += size
                }
                val (size, promise) = requests.removeFirstOrNull() ?: break
                try {
                    val response = promise.retrieve(timeoutMs, TimeUnit.MILLISECONDS)
                    if (stopped) continue
                    when (response.type) {
                        PacketType.DATA -> {
                            val count = response.readUInt32AsInt()
                            if (count < 0 || count > size) {
                                throw SFTPException("Server returned $count bytes for a $size byte read")
                            }
                            response.readRawBytes(buffer, start + received, count)
                            received += count
                            if (count < size) stopped = true
                        }
                        PacketType.STATUS -> {
                            response.ensureStatusIs(Response.StatusCode.EOF)
                            endOfFile = true
                            stopped = true
                        }
                        else -> throw SFTPException("Unexpected packet: ${response.type}")
                    }
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                    stopped = true
                }
            }
            failure?.let { if (received == 0 || it.causes().any { c -> c is TimeoutException }) throw it }
            return if (received == 0 && endOfFile) -1 else received
        }

        private fun writeAt(offset: Long, buffer: ByteArray, start: Int, length: Int) {
            ensureFileOpen()
            checkRange(offset, buffer, start, length)
            var done = 0
            while (done < length) {
                val size = minOf(CHUNK_SIZE, length - done)
                val request =
                    engine
                        .newRequest(PacketType.WRITE)
                        .putString(handle)
                        .putUInt64(offset + done)
                        .putString(buffer, start + done, size)
                val promise = engine.request(request)
                val overflow =
                    synchronized(pendingWrites) {
                        pendingWrites.addLast(promise)
                        if (pendingWrites.size > MAX_IN_FLIGHT) pendingWrites.removeFirst() else null
                    }
                if (overflow != null) confirm(overflow)
                done += size
            }
        }

        private fun confirm(promise: Promise<Response, SFTPException>) {
            try {
                promise.retrieve(timeoutMs, TimeUnit.MILLISECONDS).ensureStatusPacketIsOK()
            } catch (error: Throwable) {
                drainWrites()
                throw error
            }
        }

        private fun drainWrites() {
            while (true) {
                val promise = synchronized(pendingWrites) { pendingWrites.removeFirstOrNull() } ?: return
                runCatching { promise.retrieve(timeoutMs, TimeUnit.MILLISECONDS) }
            }
        }

        private fun awaitWrites() {
            while (true) {
                val promise = synchronized(pendingWrites) { pendingWrites.removeFirstOrNull() } ?: return
                confirm(promise)
            }
        }

        private fun currentSize(): Long {
            ensureFileOpen()
            awaitWrites()
            val attributes = fstat(handle)
            if (!attributes.has(FileAttributes.Flag.SIZE)) {
                throw SshException(SshException.Kind.PROTOCOL, message = "Server omitted the file size")
            }
            return attributes.size
        }

        private fun truncate(size: Long) {
            require(size >= 0)
            ensureFileOpen()
            awaitWrites()
            exec(
                    engine
                        .newRequest(PacketType.FSETSTAT)
                        .putString(handle)
                        .putFileAttributes(FileAttributes.Builder().withSize(size).build())
                )
                .ensureStatusPacketIsOK()
        }

        private fun closeFile() {
            if (!closed.compareAndSet(false, true)) return
            try {
                awaitWrites()
            } catch (error: Throwable) {
                runCatching { closeHandle(handle) }.exceptionOrNull()?.let { error.addSuppressed(it) }
                throw error
            }
            closeHandle(handle)
        }
    }

    private companion object {
        const val CHUNK_SIZE = 32 * 1024
        const val MAX_IN_FLIGHT = 64

        val protocolReasons =
            setOf(
                DisconnectReason.KEY_EXCHANGE_FAILED,
                DisconnectReason.PROTOCOL_ERROR,
                DisconnectReason.MAC_ERROR,
                DisconnectReason.PROTOCOL_VERSION_NOT_SUPPORTED,
            )
    }
}

private val SFTPException.isFailure: Boolean
    get() = statusCode == Response.StatusCode.FAILURE

private val FileAttributes.fileType: SftpFileType
    get() =
        if (!has(FileAttributes.Flag.MODE)) {
            SftpFileType.OTHER
        } else {
            when (type) {
                FileMode.Type.REGULAR -> SftpFileType.FILE
                FileMode.Type.DIRECTORY -> SftpFileType.DIRECTORY
                FileMode.Type.SYMLINK -> SftpFileType.SYMLINK
                else -> SftpFileType.OTHER
            }
        }

private fun Throwable.causes(): Sequence<Throwable> {
    val seen = mutableSetOf<Throwable>()
    return generateSequence(this) { it.cause }.takeWhile { seen.add(it) }
}

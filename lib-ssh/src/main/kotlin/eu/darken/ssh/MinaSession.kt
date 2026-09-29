package eu.darken.ssh

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.concurrent.CompletableFuture
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
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.config.hosts.HostConfigEntry
import org.apache.sshd.client.future.ConnectFuture
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.kex.KexProposalOption
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.common.util.buffer.BufferException
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.sftp.client.SftpVersionSelector
import org.apache.sshd.sftp.client.impl.DefaultSftpClient
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.common.SshException as MinaSshException

/**
 * One SSH connection with one SFTP v3 channel. MINA carries the transport, the channel and request
 * framing; requests are encoded here so that reads and writes pipeline on a real file handle and no
 * call goes beyond SFTP v3.
 */
internal class MinaSession(config: SftpConfig) : SftpSession {
    private val timeoutMs = config.requestTimeout.inWholeMilliseconds
    private val requestTimeout = Duration.ofMillis(timeoutMs)
    private val connectTimeoutMs = config.connectTimeout.inWholeMilliseconds
    private val disconnected = AtomicBoolean(false)
    @Volatile private var connecting: ConnectFuture? = null
    @Volatile private var session: ClientSession? = null
    @Volatile private var sftp: DefaultSftpClient? = null
    private lateinit var extensions: Set<String>

    override val connected: Boolean
        get() = !disconnected.get() && session?.isOpen == true && sftp?.isOpen == true

    /** The ciphers key exchange settled on, client-to-server and server-to-client, as wire names. */
    internal val negotiatedCiphers: Pair<String?, String?>
        get() = session.let {
            it?.getNegotiatedKexParameter(KexProposalOption.C2SENC) to
                it?.getNegotiatedKexParameter(KexProposalOption.S2CENC)
        }

    suspend fun open(
        client: SshClient,
        endpoint: SftpEndpoint,
        username: String,
        handshake: Handshake,
    ) = io {
        val host = HostConfigEntry("", endpoint.host, endpoint.port, username).apply { isIdentitiesOnly = true }
        val future =
            try {
                client.connect(host, handshake.context, null).also { connecting = it }
            } catch (error: IOException) {
                throw failure(error)
            }
        if (disconnected.get()) future.cancel()
        if (!future.await(connectTimeoutMs)) {
            future.cancel()
            throw SshException(SshException.Kind.TRANSPORT, message = "Connect timed out")
        }
        future.exception?.let { throw failure(it) }
        val session = future.clientSession ?: throw failure(EOFException("Connect was cancelled"))
        this.session = session
        if (disconnected.get()) session.close(true)
        ensureOpen()

        // The host key is decided during key exchange; authentication starts only after it passed.
        val state = session.waitFor(listOf(ClientSessionEvent.WAIT_AUTH, ClientSessionEvent.CLOSED), timeoutMs)
        handshake.refusal?.let { throw it }
        if (ClientSessionEvent.WAIT_AUTH !in state) {
            throw if (ClientSessionEvent.CLOSED in state) {
                failure(handshake.failure ?: EOFException("Connection closed during key exchange"))
            } else {
                failure(RequestTimeout("Key exchange timed out"))
            }
        }

        val keyPair = handshake.keyPair
        keyPair?.let { session.addPublicKeyIdentity(it) }
        try {
            val auth = session.auth()
            if (!auth.await(timeoutMs)) throw failure(RequestTimeout("Authentication timed out"))
            if (!auth.isSuccess) {
                val error = auth.exception ?: EOFException("Authentication failed")
                if (error is MinaSshException &&
                    error.disconnectCode == SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE
                ) {
                    throw SshException(SshException.Kind.AUTHENTICATION, cause = error)
                }
                throw failure(error)
            }
        } finally {
            keyPair?.let { session.removePublicKeyIdentity(it) }
            handshake.wipe()
        }

        try {
            val sftp = DefaultSftpClient(session, SftpVersionSelector.fixedVersionSelector(SFTP_V3), null)
            this.sftp = sftp
            if (sftp.version != SFTP_V3) {
                throw SshException(SshException.Kind.PROTOCOL, message = "Server chose SFTP ${sftp.version}")
            }
            extensions = sftp.serverExtensions.keys.toSet()
        } catch (error: Exception) {
            throw failure(error)
        }
    }

    override suspend fun canonicalize(path: String): SftpPath = op {
        require(path.none { it == '\u0000' })
        val reply = exec(SftpConstants.SSH_FXP_REALPATH) { putString(path, Charsets.UTF_8) }
        SftpPath.parseAbsolute(reply.firstName())
            ?: throw SshException(SshException.Kind.PROTOCOL, message = "Server returned non-canonical path")
    }

    override suspend fun stat(path: SftpPath): SftpEntry = op { entry(path, stat(path.wire)) }

    override suspend fun lstat(path: SftpPath): SftpEntry = op { entry(path, lstat(path.wire)) }

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
            exec(SftpConstants.SSH_FXP_MKDIR) {
                putPath(path.wire)
                putAttributes()
            }
                .ensureOk()
        } catch (error: StatusException) {
            if (error.isFailure && lstatOrNull(path.wire) != null) throw exists(error)
            throw error
        }
    }

    override suspend fun delete(path: SftpPath, recursive: Boolean) = op {
        if (lstat(path.wire).type == SftpFileType.DIRECTORY) {
            if (recursive) deleteChildren(path)
            removeDirectory(path)
        } else {
            remove(path)
        }
    }

    /** Plain SFTP v3 rename: no posix-rename or overwrite flag, so the server never replaces. */
    override suspend fun rename(source: SftpPath, destination: SftpPath) = op {
        if (lstatOrNull(destination.wire) != null) throw exists(null)
        try {
            exec(SftpConstants.SSH_FXP_RENAME) {
                putPath(source.wire)
                putPath(destination.wire)
            }
                .ensureOk()
        } catch (error: StatusException) {
            if (error.isFailure && lstatOrNull(destination.wire) != null) throw exists(error)
            throw error
        }
    }

    override suspend fun readLink(path: SftpPath): String = op {
        exec(SftpConstants.SSH_FXP_READLINK) { putPath(path.wire) }.firstName()
    }

    override suspend fun symlink(link: SftpPath, target: String): Unit = op {
        require(target.isNotEmpty() && target.none { it == '\u0000' })
        // OpenSSH, and servers following it, expect the target before the link path.
        exec(SftpConstants.SSH_FXP_SYMLINK) {
            putPath(target)
            putPath(link.wire)
        }
            .ensureOk()
    }

    override suspend fun openFile(path: SftpPath, mode: SftpOpenMode): SftpFile = op {
        val readWrite = SftpConstants.SSH_FXF_READ or SftpConstants.SSH_FXF_WRITE
        val flags =
            when (mode) {
                SftpOpenMode.READ -> SftpConstants.SSH_FXF_READ
                SftpOpenMode.READ_WRITE -> readWrite or SftpConstants.SSH_FXF_CREAT
                SftpOpenMode.CREATE_NEW -> readWrite or SftpConstants.SSH_FXF_CREAT or SftpConstants.SSH_FXF_EXCL
                SftpOpenMode.OVERWRITE -> readWrite or SftpConstants.SSH_FXF_CREAT or SftpConstants.SSH_FXF_TRUNC
            }
        val handle =
            try {
                exec(SftpConstants.SSH_FXP_OPEN) {
                    putPath(path.wire)
                    putUInt(flags.toLong())
                    putAttributes()
                }
                    .expect(SftpConstants.SSH_FXP_HANDLE)
                    .bytes
            } catch (error: StatusException) {
                if (error.isFailure) {
                    if (mode == SftpOpenMode.CREATE_NEW && lstatOrNull(path.wire) != null) throw exists(error)
                    if (statOrNull(path.wire)?.type == SftpFileType.DIRECTORY) {
                        throw SshException(SshException.Kind.IS_DIRECTORY, cause = error)
                    }
                }
                throw error
            }
        val type =
            try {
                fstat(handle).type
            } catch (error: Exception) {
                runCatching { closeHandle(handle) }
                throw error
            }
        if (type == SftpFileType.DIRECTORY) {
            runCatching { closeHandle(handle) }
            throw SshException(SshException.Kind.IS_DIRECTORY)
        }
        MinaFile(handle)
    }

    override suspend fun setModifiedAt(path: SftpPath, instant: Instant) = op {
        require(instant.epochSeconds in 0..0xFFFF_FFFFL) { "Out of SFTP v3 time range" }
        val accessed = stat(path.wire).accessed ?: instant.epochSeconds
        setStat(path.wire) { putAttributes(accessed = accessed, modified = instant.epochSeconds) }
    }

    override suspend fun setPermissions(path: SftpPath, mode: Int) = op {
        require(mode in 0..0x0FFF) { "Mode must be within 07777" }
        setStat(path.wire) { putAttributes(mode = mode) }
    }

    override suspend fun capacity(path: SftpPath): SftpCapacity? = op {
        if (STATVFS !in extensions) return@op null
        val reply =
            exec(SftpConstants.SSH_FXP_EXTENDED) {
                putString(STATVFS)
                putPath(path.wire)
            }
                .expect(SftpConstants.SSH_FXP_EXTENDED_REPLY)
        val blockSize = reply.long
        val fragmentSize = reply.long.takeIf { it > 0 } ?: blockSize
        val blocks = reply.long
        reply.long // f_bfree includes blocks reserved for root
        val available = reply.long
        try {
            SftpCapacity(Math.multiplyExact(fragmentSize, blocks), Math.multiplyExact(fragmentSize, available))
        } catch (error: ArithmeticException) {
            throw SshException(SshException.Kind.PROTOCOL, message = "Capacity overflow", cause = error)
        }
    }

    override fun disconnect() {
        if (!disconnected.compareAndSet(false, true)) return
        runCatching { connecting?.cancel() }
        runCatching { session?.close(true) }
    }

    override suspend fun close() {
        if (disconnected.get()) return
        try {
            io {
                runCatching { sftp?.close() }
                session?.close(false)?.await(timeoutMs)
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
        val sftp = sftp
        if (sftp != null && (!sftp.isOpen || session?.isOpen != true)) disconnect()
        if (disconnected.get()) {
            throw SshException(SshException.Kind.TRANSPORT, message = "Session is disconnected")
        }
    }

    private fun failure(error: Throwable): Throwable {
        if (error is SshException || error is Error) return error
        if (error.causes().any { it is RequestTimeout }) {
            disconnect()
            return SshException(SshException.Kind.TRANSPORT, message = "Request timed out", cause = error)
        }
        if (error.causes().any { it is UnresolvedAddressException }) {
            return SshException(SshException.Kind.TRANSPORT, message = "Unresolved host", cause = error)
        }
        if (error is IllegalArgumentException) return error
        if (error is StatusException) {
            val kind = SshException.kindOfStatus(error.code)
            return SshException(kind, message = "SFTP status ${error.code}: ${error.message}", cause = error)
        }
        val protocolFailure = error.causes().any { it is MinaSshException && it.disconnectCode in protocolReasons }
        if (protocolFailure) return SshException(SshException.Kind.PROTOCOL, cause = error)
        val transportFailure =
            disconnected.get() ||
                session?.isOpen == false ||
                sftp?.isOpen == false ||
                error.causes().any {
                    it is SocketException ||
                        it is EOFException ||
                        it is java.net.UnknownHostException ||
                        it is java.net.SocketTimeoutException ||
                        it is java.nio.channels.ClosedChannelException
                }
        val kind =
            when {
                transportFailure -> SshException.Kind.TRANSPORT
                error is ProtocolViolation || error is MinaSshException || error is BufferException ->
                    SshException.Kind.PROTOCOL
                error is IOException -> SshException.Kind.TRANSPORT
                else -> SshException.Kind.OTHER
            }
        return SshException(kind, message = "SSH operation failed: $kind (${error.message})", cause = error)
    }

    private fun exists(cause: Throwable?) = SshException(SshException.Kind.ALREADY_EXISTS, cause = cause)

    // --- SFTP v3 requests ---

    private class Reply(val type: Int, val buffer: Buffer)

    private fun request(capacity: Int, build: Buffer.() -> Unit): Buffer =
        ByteArrayBuffer(capacity + REQUEST_HEADER, false)
            .apply {
                // Room for the length, type and id that DefaultSftpClient prepends in place.
                rpos(REQUEST_HEADER)
                wpos(REQUEST_HEADER)
            }
            .apply(build)

    private fun send(type: Int, capacity: Int = 256, build: Buffer.() -> Unit): Int =
        checkNotNull(sftp).send(type, request(capacity, build))

    /** A request timeout disconnects, so later receives on this session fail at once. */
    private fun reply(id: Int): Reply {
        val buffer = checkNotNull(sftp).receive(id, requestTimeout)
        if (buffer == null) {
            disconnect()
            throw RequestTimeout("No reply to request $id within $requestTimeout")
        }
        buffer.getInt() // length
        val type = buffer.getUByte()
        buffer.getInt() // id
        return Reply(type, buffer)
    }

    private fun exec(type: Int, build: Buffer.() -> Unit): Reply = reply(send(type, build = build))

    private fun Reply.expect(expected: Int): Buffer {
        if (type == expected) return buffer
        if (type == SftpConstants.SSH_FXP_STATUS) throw status()
        throw ProtocolViolation("Unexpected SFTP packet $type, expected $expected")
    }

    private fun Reply.status(): StatusException {
        val code = buffer.getInt()
        val message = runCatching { buffer.getString() }.getOrDefault("")
        return StatusException(code, message)
    }

    private fun Reply.ensureStatus(expected: Int) {
        if (type != SftpConstants.SSH_FXP_STATUS) throw ProtocolViolation("Unexpected SFTP packet $type")
        val status = status()
        if (status.code != expected) throw status
    }

    private fun Reply.ensureOk() = ensureStatus(SftpConstants.SSH_FX_OK)

    private fun Reply.firstName(): String {
        val names = expect(SftpConstants.SSH_FXP_NAME)
        if (names.getInt() < 1) throw ProtocolViolation("Empty name reply")
        return names.getString(Charsets.UTF_8)
    }

    private fun Buffer.putPath(value: String) = putString(value, Charsets.UTF_8)

    private fun Buffer.putAttributes(size: Long? = null, mode: Int? = null, accessed: Long? = null, modified: Long? = null) {
        var flags = 0
        if (size != null) flags = flags or SftpConstants.SSH_FILEXFER_ATTR_SIZE
        if (mode != null) flags = flags or SftpConstants.SSH_FILEXFER_ATTR_PERMISSIONS
        if (accessed != null && modified != null) flags = flags or SftpConstants.SSH_FILEXFER_ATTR_ACMODTIME
        putUInt(flags.toLong())
        if (size != null) putLong(size)
        if (mode != null) putUInt(mode.toLong())
        if (accessed != null && modified != null) {
            putUInt(accessed)
            putUInt(modified)
        }
    }

    private fun Buffer.getAttributes(): Attributes {
        val flags = getInt()
        val size = if (flags and SftpConstants.SSH_FILEXFER_ATTR_SIZE != 0) getLong() else null
        var uid: Int? = null
        var gid: Int? = null
        if (flags and SftpConstants.SSH_FILEXFER_ATTR_UIDGID != 0) {
            uid = getInt()
            gid = getInt()
        }
        val mode = if (flags and SftpConstants.SSH_FILEXFER_ATTR_PERMISSIONS != 0) getInt() else null
        var accessed: Long? = null
        var modified: Long? = null
        if (flags and SftpConstants.SSH_FILEXFER_ATTR_ACMODTIME != 0) {
            accessed = getUInt()
            modified = getUInt()
        }
        if (flags and SftpConstants.SSH_FILEXFER_ATTR_EXTENDED != 0) {
            repeat(getInt()) {
                getBytes()
                getBytes()
            }
        }
        return Attributes(size, uid, gid, mode, accessed, modified)
    }

    private fun stat(path: String): Attributes =
        exec(SftpConstants.SSH_FXP_STAT) { putPath(path) }.expect(SftpConstants.SSH_FXP_ATTRS).getAttributes()

    private fun lstat(path: String): Attributes =
        exec(SftpConstants.SSH_FXP_LSTAT) { putPath(path) }.expect(SftpConstants.SSH_FXP_ATTRS).getAttributes()

    private fun statOrNull(path: String): Attributes? =
        try {
            stat(path)
        } catch (error: StatusException) {
            if (error.code == SftpConstants.SSH_FX_NO_SUCH_FILE) null else throw error
        }

    private fun lstatOrNull(path: String): Attributes? =
        try {
            lstat(path)
        } catch (error: StatusException) {
            if (error.code == SftpConstants.SSH_FX_NO_SUCH_FILE) null else throw error
        }

    private fun setStat(path: String, attributes: Buffer.() -> Unit) {
        exec(SftpConstants.SSH_FXP_SETSTAT) {
            putPath(path)
            attributes()
        }
            .ensureOk()
    }

    private fun remove(path: SftpPath) {
        exec(SftpConstants.SSH_FXP_REMOVE) { putPath(path.wire) }.ensureOk()
    }

    private fun fstat(handle: ByteArray): Attributes =
        exec(SftpConstants.SSH_FXP_FSTAT) { putBytes(handle) }.expect(SftpConstants.SSH_FXP_ATTRS).getAttributes()

    private fun closeHandle(handle: ByteArray) {
        exec(SftpConstants.SSH_FXP_CLOSE) { putBytes(handle) }.ensureOk()
    }

    private inner class Directory(val handle: ByteArray) : SftpResource {
        override suspend fun close() = op { closeHandle(handle) }
    }

    private suspend fun directory(path: SftpPath): Directory = op { Directory(openDir(path)) }

    private fun openDir(path: SftpPath): ByteArray =
        try {
            exec(SftpConstants.SSH_FXP_OPENDIR) { putPath(path.wire) }.expect(SftpConstants.SSH_FXP_HANDLE).bytes
        } catch (error: StatusException) {
            if (error.code == SftpConstants.SSH_FX_NO_SUCH_FILE || error.isFailure) {
                val existing = statOrNull(path.wire)
                if (existing != null && existing.type != SftpFileType.DIRECTORY) {
                    throw SshException(SshException.Kind.NOT_DIRECTORY, cause = error)
                }
            }
            throw error
        }

    /** Returns `null` at the end of the directory. */
    private fun readDir(handle: ByteArray): List<Pair<String, Attributes>>? {
        val reply = exec(SftpConstants.SSH_FXP_READDIR) { putBytes(handle) }
        return when (reply.type) {
            SftpConstants.SSH_FXP_NAME -> {
                val count = reply.buffer.getInt()
                val entries = ArrayList<Pair<String, Attributes>>(count.coerceIn(0, 4096))
                repeat(count) {
                    val name = reply.buffer.getString(Charsets.UTF_8)
                    reply.buffer.getBytes() // longname
                    val attributes = reply.buffer.getAttributes()
                    if (SftpPath.isValidSegment(name)) entries += name to attributes
                }
                entries
            }
            else -> {
                reply.ensureStatus(SftpConstants.SSH_FX_EOF)
                null
            }
        }
    }

    private fun listAll(path: SftpPath): List<Pair<String, Attributes>> {
        val handle = openDir(path)
        try {
            val entries = ArrayList<Pair<String, Attributes>>()
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
                attributes.type == SftpFileType.DIRECTORY && lstat(child.wire).type == SftpFileType.DIRECTORY
            if (isDirectory) {
                deleteChildren(child)
                removeDirectory(child)
            } else {
                remove(child)
            }
        }
    }

    private fun removeDirectory(path: SftpPath) {
        try {
            exec(SftpConstants.SSH_FXP_RMDIR) { putPath(path.wire) }.ensureOk()
        } catch (error: StatusException) {
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

    private fun entry(path: SftpPath, attributes: Attributes): SftpEntry =
        SftpEntry(
            path = path,
            type = attributes.type,
            size = attributes.size ?: 0,
            modifiedAt = Instant.fromEpochSeconds(attributes.modified ?: 0),
            permissions = attributes.mode?.and(0x0FFF),
            uid = attributes.uid,
            gid = attributes.gid,
        )

    private inner class MinaFile(private val handle: ByteArray) : SftpFile {
        private val closed = AtomicBoolean(false)
        private val pendingWrites = ArrayDeque<Int>()

        /** Fits each SSH_FXP_WRITE into one channel packet; MINA would send a larger one as two. */
        private val writeChunk =
            (checkNotNull(sftp).clientChannel.remoteWindow.packetSize - WRITE_OVERHEAD - handle.size)
                .coerceIn(MIN_WRITE_CHUNK.toLong(), CHUNK_SIZE.toLong())
                .toInt()

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
            val requests = ArrayDeque<Pair<Int, Int>>()
            var requested = 0
            var received = 0
            var endOfFile = false
            var stopped = false
            var failure: Throwable? = null
            while (true) {
                while (!stopped && requested < length && requests.size < MAX_IN_FLIGHT) {
                    val size = minOf(CHUNK_SIZE, length - requested)
                    val id =
                        send(SftpConstants.SSH_FXP_READ, handle.size + 16) {
                            putBytes(handle)
                            putLong(offset + requested)
                            putUInt(size.toLong())
                        }
                    requests.addLast(size to id)
                    requested += size
                }
                val (size, id) = requests.removeFirstOrNull() ?: break
                try {
                    val reply = reply(id)
                    if (stopped) continue
                    when (reply.type) {
                        SftpConstants.SSH_FXP_DATA -> {
                            val count = reply.buffer.getInt()
                            if (count < 0 || count > size || count > reply.buffer.available()) {
                                throw ProtocolViolation("Server returned $count bytes for a $size byte read")
                            }
                            reply.buffer.getRawBytes(buffer, start + received, count)
                            received += count
                            if (count < size) stopped = true
                        }
                        else -> {
                            reply.ensureStatus(SftpConstants.SSH_FX_EOF)
                            endOfFile = true
                            stopped = true
                        }
                    }
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                    stopped = true
                }
            }
            failure?.let { if (received == 0 || it.causes().any { c -> c is RequestTimeout }) throw it }
            return if (received == 0 && endOfFile) -1 else received
        }

        private fun writeAt(offset: Long, buffer: ByteArray, start: Int, length: Int) {
            ensureFileOpen()
            checkRange(offset, buffer, start, length)
            var done = 0
            while (done < length) {
                val size = minOf(writeChunk, length - done)
                val id =
                    send(SftpConstants.SSH_FXP_WRITE, handle.size + size + 16) {
                        putBytes(handle)
                        putLong(offset + done)
                        putBytes(buffer, start + done, size)
                    }
                val overflow =
                    synchronized(pendingWrites) {
                        pendingWrites.addLast(id)
                        if (pendingWrites.size > MAX_IN_FLIGHT) pendingWrites.removeFirst() else null
                    }
                if (overflow != null) confirm(overflow)
                done += size
            }
        }

        private fun confirm(id: Int) {
            try {
                reply(id).ensureOk()
            } catch (error: Throwable) {
                drainWrites()
                throw error
            }
        }

        private fun drainWrites() {
            while (true) {
                val id = synchronized(pendingWrites) { pendingWrites.removeFirstOrNull() } ?: return
                runCatching { reply(id) }
            }
        }

        private fun awaitWrites() {
            while (true) {
                val id = synchronized(pendingWrites) { pendingWrites.removeFirstOrNull() } ?: return
                confirm(id)
            }
        }

        private fun currentSize(): Long {
            ensureFileOpen()
            awaitWrites()
            return fstat(handle).size
                ?: throw SshException(SshException.Kind.PROTOCOL, message = "Server omitted the file size")
        }

        private fun truncate(size: Long) {
            require(size >= 0)
            ensureFileOpen()
            awaitWrites()
            exec(SftpConstants.SSH_FXP_FSETSTAT) {
                putBytes(handle)
                putAttributes(size = size)
            }
                .ensureOk()
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
        const val SFTP_V3 = 3
        const val STATVFS = "statvfs@openssh.com"
        const val REQUEST_HEADER = 4 + 1 + 4
        const val CHUNK_SIZE = 32 * 1024
        const val MIN_WRITE_CHUNK = 4 * 1024

        /** Header, handle length, offset and data length around an SSH_FXP_WRITE's handle and data. */
        const val WRITE_OVERHEAD = REQUEST_HEADER + 4 + 8 + 4
        const val MAX_IN_FLIGHT = 64

        val protocolReasons =
            setOf(
                SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED,
                SshConstants.SSH2_DISCONNECT_PROTOCOL_ERROR,
                SshConstants.SSH2_DISCONNECT_MAC_ERROR,
                SshConstants.SSH2_DISCONNECT_PROTOCOL_VERSION_NOT_SUPPORTED,
            )
    }
}

/** SFTP v3 attributes; `null` where the server omitted the field. */
private class Attributes(
    val size: Long?,
    val uid: Int?,
    val gid: Int?,
    val mode: Int?,
    val accessed: Long?,
    val modified: Long?,
) {
    val type: SftpFileType
        get() =
            when (mode?.and(0xF000)) {
                null -> SftpFileType.OTHER
                0x8000 -> SftpFileType.FILE
                0x4000 -> SftpFileType.DIRECTORY
                0xA000 -> SftpFileType.SYMLINK
                else -> SftpFileType.OTHER
            }
}

private class StatusException(val code: Int, message: String) : IOException(message) {
    val isFailure: Boolean
        get() = code == SftpConstants.SSH_FX_FAILURE
}

private class ProtocolViolation(message: String) : IOException(message)

private class RequestTimeout(message: String) : IOException(message)

private fun Throwable.causes(): Sequence<Throwable> {
    val seen = mutableSetOf<Throwable>()
    return generateSequence(this) { it.cause }.takeWhile { seen.add(it) }
}

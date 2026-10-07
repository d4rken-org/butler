package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.ssh.SftpBlockingFile
import eu.darken.ssh.SftpEntry
import eu.darken.ssh.SftpFileType
import eu.darken.ssh.SftpOpenMode
import eu.darken.ssh.use
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import okio.FileHandle
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import eu.darken.ssh.SftpPath as ServerPath

/** SFTP gateway adapters backed by leases from [SftpConnectionPool]. */
@Singleton
class SftpFileSystemOps @Inject constructor(
    private val pool: SftpConnectionPool,
    private val dispatcherProvider: DispatcherProvider,
) : FileSystemOps<SftpPath, SftpPathLookup> {

    private suspend fun <R> read(
        path: SftpPath,
        operation: String,
        block: suspend (SftpConnectionPool.Lease) -> R,
    ): R = runOp(path, operation, write = false, retry = true, block)

    private suspend fun <R> mutate(
        path: SftpPath,
        operation: String,
        block: suspend (SftpConnectionPool.Lease) -> R,
    ): R = runOp(path, operation, write = true, retry = false, block)

    private suspend fun <R> runOp(
        path: SftpPath,
        operation: String,
        write: Boolean,
        retry: Boolean,
        block: suspend (SftpConnectionPool.Lease) -> R,
    ): R = withContext(dispatcherProvider.IO) {
        try {
            pool.use(path, retryOnTransportLoss = retry) { lease -> block(lease) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SftpStatusMapper.mapOperation(e, path, operation, write)
        }
    }

    override suspend fun lookup(path: SftpPath, options: LookupOptions): SftpPathLookup = try {
        read(path, "lookup") { lease ->
            lease.toLookup(path, lease.session.lstat(lease.serverPath(path)))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "lookup($path) failed: ${e.asLog()}" }
        if (!options.fallbackToUnknown) throw e
        SftpPathLookup(
            lookedUp = path,
            fileType = FileType.UNKNOWN,
            size = null,
            modifiedAt = null,
            error = e.message,
        )
    }

    override suspend fun listFiles(path: SftpPath): List<SftpPath> =
        lookupFiles(path, LookupOptions()).map { it.lookedUp }

    /** Listing entries carry their attributes, only a symbolic link costs one more round trip. */
    override suspend fun lookupFiles(path: SftpPath, options: LookupOptions): List<SftpPathLookup> =
        read(path, "lookupFiles") { lease ->
            lease.session.list(lease.serverPath(path)).toList().map { entry ->
                lease.toLookup(path.child(entry.path.segments.last()), entry)
            }
        }

    override suspend fun exists(path: SftpPath): Boolean = try {
        read(path, "exists") { lease ->
            lease.session.lstat(lease.serverPath(path))
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, VERBOSE) { "exists($path) -> false ($e)" }
        false
    }

    /**
     * A base path that no longer exists already fails while the session resolves it, which is as
     * definitive an absence as a failed lstat below it.
     */
    override suspend fun existsStrict(path: SftpPath): Existence = try {
        read(path, "existsStrict") { lease ->
            lease.session.lstat(lease.serverPath(path))
            Existence.PRESENT
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (SftpStatusMapper.isMissing(e)) {
            Existence.ABSENT
        } else {
            log(TAG, WARN) { "existsStrict($path) could not be answered: ${e.asLog()}" }
            Existence.UNKNOWN
        }
    }

    /** Recursive deletion unlinks symbolic links and never descends through them. */
    override suspend fun delete(path: SftpPath, recursive: Boolean): Boolean = mutate(path, "delete") { lease ->
        try {
            lease.session.delete(lease.serverPath(path), recursive)
            true
        } catch (e: Exception) {
            if (SftpStatusMapper.isMissing(e)) false else throw e
        }
    }

    override suspend fun createDir(path: SftpPath, createParents: Boolean) {
        val parent = path.parent
        if (createParents && parent != null && parent.segments.isNotEmpty() && !exists(parent)) {
            createDir(parent, createParents = true)
        }
        mutate(path, "createDir") { lease -> lease.session.mkdir(lease.serverPath(path)) }
    }

    override suspend fun createFile(path: SftpPath, createParents: Boolean) {
        val parent = path.parent
        if (createParents && parent != null && parent.segments.isNotEmpty() && !exists(parent)) {
            createDir(parent, createParents = true)
        }
        mutate(path, "createFile") { lease ->
            lease.session.openFile(lease.serverPath(path), SftpOpenMode.CREATE_NEW).use {}
        }
    }

    /** A server-side rename, which never replaces an existing destination. */
    override suspend fun move(source: SftpPath, destination: SftpPath): MoveOutcome {
        if (source.locationId != destination.locationId) {
            return MoveOutcome.NotSupported("Source and destination are different network locations")
        }
        return mutate(source, "move") { lease ->
            lease.session.rename(lease.serverPath(source), lease.serverPath(destination))
            MoveOutcome.Moved
        }
    }

    private suspend fun <T : java.io.Closeable> openResource(
        path: SftpPath,
        operation: String,
        write: Boolean,
        block: suspend (SftpConnectionPool.Lease) -> T,
    ): T {
        var lease: SftpConnectionPool.Lease? = null
        var resource: T? = null
        try {
            return withContext(dispatcherProvider.IO) {
                lease = pool.acquire(path.locationId)
                block(lease).also { resource = it }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + dispatcherProvider.IO) {
                runCatching { resource?.close() }
                    .exceptionOrNull()
                    ?.let { if (it !== error) error.addSuppressed(it) }
                lease?.close()
            }
            if (error is CancellationException) throw error
            throw SftpStatusMapper.mapOperation(error, path, operation, write)
        }
    }

    override suspend fun openInputStream(path: SftpPath): InputStream =
        openResource(path, "openInputStream", false) { lease ->
            val file = lease.session.openFile(lease.serverPath(path)).blocking()
            val stream = object : InputStream() {
                private var position = 0L

                override fun read(): Int =
                    ByteArray(1).let { if (read(it, 0, 1) == -1) -1 else it[0].toInt() and 255 }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = try {
                    file.read(position, bytes, offset, length).also { if (it > 0) position += it }
                } catch (error: Exception) {
                    throw SftpStatusMapper.mapOperation(error, path, "read", false)
                }

                override fun close() {
                    try {
                        file.close()
                    } finally {
                        lease.close()
                    }
                }
            }
            java.io.BufferedInputStream(stream, 256 * 1024)
        }

    /**
     * Writes are pipelined: a rejection by the server can surface from a later write, the flush or
     * the close, all of which report it as a write failure of [path].
     */
    override suspend fun openOutputStream(path: SftpPath, append: Boolean): OutputStream =
        openResource(path, "openOutputStream", true) { lease ->
            val file = lease.session
                .openFile(lease.serverPath(path), if (append) SftpOpenMode.READ_WRITE else SftpOpenMode.OVERWRITE)
                .blocking()
            try {
                var position = if (append) file.size() else 0L
                val stream = object : OutputStream() {
                    private var needsFlush = true

                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        try {
                            file.write(position, bytes, offset, length)
                            position += length
                            if (length > 0) needsFlush = true
                        } catch (error: Exception) {
                            throw SftpStatusMapper.mapOperation(error, path, "write", true)
                        }
                    }

                    override fun flush() {
                        if (!needsFlush) return
                        try {
                            file.flush()
                        } catch (error: Exception) {
                            throw SftpStatusMapper.mapOperation(error, path, "write", true)
                        }
                        needsFlush = false
                    }

                    override fun close() {
                        try {
                            file.close()
                        } catch (error: Exception) {
                            throw SftpStatusMapper.mapOperation(error, path, "write", true)
                        } finally {
                            lease.close()
                        }
                    }
                }
                java.io.BufferedOutputStream(stream, 1024 * 1024)
            } catch (error: Throwable) {
                runCatching { file.close() }
                    .exceptionOrNull()
                    ?.let { if (it !== error) error.addSuppressed(it) }
                throw error
            }
        }

    override suspend fun file(path: SftpPath, readWrite: Boolean): FileHandle =
        openResource(path, "file", readWrite) { lease ->
            val file = lease.session.openFile(
                lease.serverPath(path),
                if (readWrite) SftpOpenMode.READ_WRITE else SftpOpenMode.READ,
            )
            SftpFileHandle(readWrite, file.blocking(), lease)
        }

    override suspend fun setModifiedAt(path: SftpPath, modifiedAt: Instant): Boolean = try {
        mutate(path, "setModifiedAt") { lease ->
            lease.session.setModifiedAt(lease.serverPath(path), modifiedAt)
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "setModifiedAt($path) failed: ${e.asLog()}" }
        false
    }

    /** Stores the absolute server path of [targetPath], e.g. `/home/darken/photos`. */
    override suspend fun createSymlink(linkPath: SftpPath, targetPath: SftpPath): Boolean {
        require(linkPath.locationId == targetPath.locationId) {
            "Can't link across network locations: $linkPath -> $targetPath"
        }
        return mutate(linkPath, "createSymlink") { lease ->
            lease.session.symlink(lease.serverPath(linkPath), lease.serverPath(targetPath).toString())
            true
        }
    }

    /**
     * A relative target is resolved against the link's directory without asking the server.
     *
     * @throws ReadException if the target lies outside the location, where no [SftpPath] can address it
     */
    override suspend fun readSymbolicLink(linkPath: SftpPath): SftpPath = read(linkPath, "readSymbolicLink") { lease ->
        val raw = lease.session.readLink(lease.serverPath(linkPath))
        lease.resolveLinkTarget(linkPath, raw)
            ?: throw ReadException("Link target '$raw' is outside this location", linkPath)
    }

    /** @throws ReadException if the resolved path lies outside the location */
    override suspend fun canonicalize(path: SftpPath): SftpPath = read(path, "canonicalize") { lease ->
        val real = lease.session.canonicalize(lease.serverPath(path).toString())
        SftpRoot.toLocation(lease.root, path.locationId, real)
            ?: throw ReadException("'$real' is outside this location", path)
    }

    override suspend fun setPermissions(path: SftpPath, permissions: Permissions): Boolean = try {
        mutate(path, "setPermissions") { lease ->
            lease.session.setPermissions(lease.serverPath(path), permissions.mode and MODE_BITS)
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "setPermissions($path) failed: ${e.asLog()}" }
        false
    }

    // SFTP v3 can only set numeric ids, and those rarely mean the same account on both ends.
    override suspend fun setOwnership(path: SftpPath, ownership: Ownership): Boolean = false

    // The server enforces access, and probing it costs a round trip per item during listings.
    override suspend fun canRead(path: SftpPath): Boolean = true

    override suspend fun canWrite(path: SftpPath): Boolean = true

    /** Capacity of the volume holding [path]; empty without `statvfs@openssh.com`. */
    override suspend fun getFileSystem(path: SftpPath): FileSystem = try {
        read(path, "getFileSystem") { lease ->
            val capacity = lease.session.capacity(lease.serverPath(path))
            FileSystem(freeSpace = capacity?.freeBytes, totalSpace = capacity?.totalBytes)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "getFileSystem($path) failed: ${e.asLog()}" }
        FileSystem()
    }

    private suspend fun SftpConnectionPool.Lease.toLookup(path: SftpPath, entry: SftpEntry): SftpPathLookup {
        val errors = mutableListOf<String>()
        var linkTarget: String? = null
        var target: SftpPath? = null
        if (entry.type == SftpFileType.SYMLINK) {
            try {
                linkTarget = session.readLink(serverPath(path))
                target = resolveLinkTarget(path, linkTarget)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.add("Link target: ${e.message}")
            }
        }
        return SftpPathLookup(
            lookedUp = path,
            fileType = when (entry.type) {
                SftpFileType.FILE -> FileType.FILE
                SftpFileType.DIRECTORY -> FileType.DIRECTORY
                SftpFileType.SYMLINK -> FileType.SYMBOLIC_LINK
                SftpFileType.OTHER -> FileType.UNKNOWN
            },
            size = entry.size,
            modifiedAt = entry.modifiedAt,
            target = target,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
            ownership = ownershipOf(entry),
            permissions = entry.permissions?.let { Permissions(it) },
            linkTarget = linkTarget,
        )
    }

    private fun SftpConnectionPool.Lease.resolveLinkTarget(linkPath: SftpPath, raw: String): SftpPath? {
        val directory = serverPath(linkPath).parent ?: ServerPath.Root
        val resolved = SftpRoot.resolveLinkTarget(directory, raw) ?: return null
        return SftpRoot.toLocation(root, linkPath.locationId, resolved)
    }

    companion object {
        val TAG = logTag("SFTP", "FileSystemOps")

        private const val MODE_BITS = 0x0FFF

        /** SFTP ids are unsigned 32-bit, e.g. nobody's 4294967294 arrives as -2. */
        internal fun ownershipOf(entry: SftpEntry): Ownership? {
            val uid = entry.uid ?: return null
            val gid = entry.gid ?: return null
            return Ownership(uid.toLong() and 0xFFFF_FFFFL, gid.toLong() and 0xFFFF_FFFFL)
        }
    }
}

private class SftpFileHandle(
    readWrite: Boolean,
    private val file: SftpBlockingFile,
    private val lease: SftpConnectionPool.Lease,
) : FileHandle(readWrite) {

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
        file.read(fileOffset, array, arrayOffset, byteCount)

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
        file.write(fileOffset, array, arrayOffset, byteCount)
    }

    override fun protectedSize(): Long = file.size()

    override fun protectedResize(size: Long) {
        file.resize(size)
    }

    override fun protectedFlush() {
        file.flush()
    }

    override fun protectedClose() {
        try {
            file.close()
        } finally {
            lease.close()
        }
    }
}

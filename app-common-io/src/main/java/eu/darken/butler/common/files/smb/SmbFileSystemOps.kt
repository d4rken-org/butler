package eu.darken.butler.common.files.smb

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
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.smb.SmbBlockingFile
import eu.darken.smb.SmbEntry
import eu.darken.smb.SmbFileType
import eu.darken.smb.SmbOpenMode
import eu.darken.smb.SmbPath as LibraryPath
import eu.darken.smb.use
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import okio.FileHandle

/** SMB gateway adapters backed by leases from [SmbConnectionPool]. */
@Singleton
class SmbFileSystemOps
@Inject
constructor(
    private val pool: SmbConnectionPool,
    private val dispatcherProvider: DispatcherProvider,
) : FileSystemOps<SmbPath, SmbPathLookup> {

    private suspend fun <R> read(
        path: SmbPath,
        operation: String,
        block: suspend (SmbConnectionPool.Lease) -> R,
    ): R = runOp(path, operation, write = false, retry = true, block)

    private suspend fun <R> mutate(
        path: SmbPath,
        operation: String,
        block: suspend (SmbConnectionPool.Lease) -> R,
    ): R = runOp(path, operation, write = true, retry = false, block)

    private suspend fun <R> runOp(
        path: SmbPath,
        operation: String,
        write: Boolean,
        retry: Boolean,
        block: suspend (SmbConnectionPool.Lease) -> R,
    ): R =
        withContext(dispatcherProvider.IO) {
            try {
                pool.use(path, retryOnTransportLoss = retry) { lease -> block(lease) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SmbStatusMapper.mapOperation(e, path, operation, write)
            }
        }

    override suspend fun lookup(path: SmbPath, options: LookupOptions): SmbPathLookup =
        try {
            read(path, "lookup") { lease ->
                lease.share.stat(lease.relativePath(path)).toLookup(path)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "lookup($path) failed: ${e.asLog()}" }
            if (!options.fallbackToUnknown) throw e
            SmbPathLookup(
                lookedUp = path,
                fileType = FileType.UNKNOWN,
                size = null,
                modifiedAt = null,
                error = e.message,
            )
        }

    override suspend fun listFiles(path: SmbPath): List<SmbPath> =
        lookupFiles(path, LookupOptions()).map { it.lookedUp }

    /** Directory pages carry metadata too, with no lookup per child. */
    override suspend fun lookupFiles(path: SmbPath, options: LookupOptions): List<SmbPathLookup> =
        read(path, "lookupFiles") { lease ->
            lease.share.list(lease.relativePath(path)).toList().map { entry ->
                entry.toLookup(path.child(entry.path.segments.last()))
            }
        }

    override suspend fun exists(path: SmbPath): Boolean =
        try {
            read(path, "exists") { lease ->
                lease.share.stat(lease.relativePath(path))
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, VERBOSE) { "exists($path) -> false ($e)" }
            false
        }

    override suspend fun existsStrict(path: SmbPath): Existence =
        try {
            read(path, "existsStrict") { lease ->
                // The lease itself is the proof: it only exists once the share was reached. That
                // proof
                // covers the share root only, a base path addresses a directory inside it and is
                // probed.
                if (path.segments.isEmpty() && lease.location.basePath.isEmpty())
                    return@read Existence.PRESENT
                try {
                    lease.share.stat(lease.relativePath(path))
                    Existence.PRESENT
                } catch (e: Exception) {
                    if (SmbStatusMapper.isMissing(e)) Existence.ABSENT else throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "existsStrict($path) could not be answered: ${e.asLog()}" }
            Existence.UNKNOWN
        }

    override suspend fun delete(path: SmbPath, recursive: Boolean): Boolean =
        mutate(path, "delete") { lease ->
            try {
                lease.share.delete(lease.relativePath(path), recursive)
                true
            } catch (e: Exception) {
                if (SmbStatusMapper.isMissing(e)) false else throw e
            }
        }

    override suspend fun createDir(path: SmbPath, createParents: Boolean) {
        val parent = path.parent
        if (createParents && parent != null && parent.segments.isNotEmpty() && !exists(parent)) {
            createDir(parent, createParents = true)
        }
        mutate(path, "createDir") { lease -> lease.share.mkdir(lease.relativePath(path)) }
    }

    override suspend fun createFile(path: SmbPath, createParents: Boolean) {
        val parent = path.parent
        if (createParents && parent != null && parent.segments.isNotEmpty() && !exists(parent)) {
            createDir(parent, createParents = true)
        }
        mutate(path, "createFile") { lease ->
            lease.share.openFile(lease.relativePath(path), SmbOpenMode.CREATE_NEW).use {}
        }
    }

    override suspend fun move(source: SmbPath, destination: SmbPath): MoveOutcome {
        if (source.locationId != destination.locationId) {
            return MoveOutcome.NotSupported(
                "Source and destination are different network locations"
            )
        }
        return mutate(source, "move") { lease ->
            lease.share.move(lease.relativePath(source), lease.relativePath(destination))
            MoveOutcome.Moved
        }
    }

    private suspend fun <T : java.io.Closeable> openResource(
        path: SmbPath,
        operation: String,
        write: Boolean,
        block: suspend (SmbConnectionPool.Lease) -> T,
    ): T {
        var lease: SmbConnectionPool.Lease? = null
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
            throw SmbStatusMapper.mapOperation(error, path, operation, write)
        }
    }

    override suspend fun openInputStream(path: SmbPath): InputStream =
        openResource(path, "openInputStream", false) { lease ->
            val file = lease.share.openFile(lease.relativePath(path)).blocking()
            val stream =
                object : InputStream() {
                    private var position = 0L

                    override fun read(): Int =
                        ByteArray(1).let { if (read(it, 0, 1) == -1) -1 else it[0].toInt() and 255 }

                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                        try {
                            file.read(position, bytes, offset, length).also {
                                if (it > 0) position += it
                            }
                        } catch (error: Exception) {
                            throw SmbStatusMapper.mapOperation(error, path, "read", false)
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

    override suspend fun openOutputStream(path: SmbPath, append: Boolean): OutputStream =
        openResource(path, "openOutputStream", true) { lease ->
            val file =
                lease.share
                    .openFile(
                        lease.relativePath(path),
                        if (append) SmbOpenMode.READ_WRITE else SmbOpenMode.OVERWRITE,
                    )
                    .blocking()
            try {
                var position = if (append) file.size() else 0L
                val stream =
                    object : OutputStream() {
                        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            try {
                                file.write(position, bytes, offset, length)
                                position += length
                            } catch (error: Exception) {
                                throw SmbStatusMapper.mapOperation(error, path, "write", true)
                            }
                        }

                        override fun flush() = file.flush()

                        override fun close() {
                            try {
                                file.close()
                            } finally {
                                lease.close()
                            }
                        }
                    }
                java.io.BufferedOutputStream(stream, 256 * 1024)
            } catch (error: Throwable) {
                runCatching { file.close() }
                    .exceptionOrNull()
                    ?.let { if (it !== error) error.addSuppressed(it) }
                throw error
            }
        }

    override suspend fun file(path: SmbPath, readWrite: Boolean): FileHandle =
        openResource(path, "file", readWrite) { lease ->
            val file =
                lease.share.openFile(
                    lease.relativePath(path),
                    if (readWrite) SmbOpenMode.READ_WRITE else SmbOpenMode.READ,
                )
            SmbFileHandle(readWrite, file.blocking(), lease)
        }

    override suspend fun setModifiedAt(path: SmbPath, modifiedAt: Instant): Boolean =
        try {
            mutate(path, "setModifiedAt") { lease ->
                lease.share.setModifiedAt(lease.relativePath(path), modifiedAt)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "setModifiedAt($path) failed: ${e.asLog()}" }
            false
        }

    override suspend fun createSymlink(linkPath: SmbPath, targetPath: SmbPath): Boolean =
        throw UnsupportedOperationException("SMB shares do not expose symlinks")

    override suspend fun readSymbolicLink(linkPath: SmbPath): SmbPath =
        throw UnsupportedOperationException("SMB shares do not expose symlinks")

    // No symlinks to resolve, an SMB path is already its own canonical form.
    override suspend fun canonicalize(path: SmbPath): SmbPath = path

    override suspend fun setPermissions(path: SmbPath, permissions: Permissions): Boolean = false

    override suspend fun setOwnership(path: SmbPath, ownership: Ownership): Boolean = false

    // The server enforces access, and probing it costs a round trip per item during listings.
    override suspend fun canRead(path: SmbPath): Boolean = true

    override suspend fun canWrite(path: SmbPath): Boolean = true

    override suspend fun getFileSystem(path: SmbPath): FileSystem =
        try {
            read(path, "getFileSystem") { lease ->
                val info = lease.share.capacity()
                FileSystem(freeSpace = info.freeBytes, totalSpace = info.totalBytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "getFileSystem($path) failed: ${e.asLog()}" }
            FileSystem()
        }

    private fun SmbEntry.toLookup(path: SmbPath) =
        SmbPathLookup(
            lookedUp = path,
            fileType =
                when (type) {
                    SmbFileType.FILE -> FileType.FILE
                    SmbFileType.DIRECTORY -> FileType.DIRECTORY
                    SmbFileType.REPARSE_POINT -> FileType.SYMBOLIC_LINK
                },
            size = size,
            modifiedAt = modifiedAt,
            createdAt = createdAt,
        )

    private fun SmbConnectionPool.Lease.relativePath(path: SmbPath): LibraryPath =
        LibraryPath(location.basePath + path.segments)

    companion object {
        val TAG = logTag("SMB", "FileSystemOps")

        fun smbPath(location: SmbLocation, path: SmbPath): String {
            return LibraryPath(location.basePath + path.segments).segments.joinToString("\\")
        }
    }
}

private class SmbFileHandle(
    readWrite: Boolean,
    private val file: SmbBlockingFile,
    private val lease: SmbConnectionPool.Lease,
) : FileHandle(readWrite) {

    override fun protectedRead(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ): Int {
        return file.read(fileOffset, array, arrayOffset, byteCount)
    }

    override fun protectedWrite(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ) {
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

package eu.darken.butler.common.files.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.msfscc.fileinformation.FileRenameInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.smb.location.SmbLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okio.FileHandle
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * SMB primitives on top of [SmbConnectionPool].
 *
 * Segment lists only become backslash-separated SMB paths inside [smbPath], the single conversion
 * point at the client boundary. Everything above this class keeps working with segments.
 */
@Singleton
class SmbFileSystemOps @Inject constructor(
    private val pool: SmbConnectionPool,
    private val dispatcherProvider: DispatcherProvider,
) : FileSystemOps<SmbPath, SmbPathLookup> {

    private suspend fun <R> read(
        path: SmbPath,
        operation: String,
        block: (SmbConnectionPool.Lease) -> R,
    ): R = runOp(path, operation, write = false, retry = true, block)

    private suspend fun <R> mutate(
        path: SmbPath,
        operation: String,
        block: (SmbConnectionPool.Lease) -> R,
    ): R = runOp(path, operation, write = true, retry = false, block)

    private suspend fun <R> runOp(
        path: SmbPath,
        operation: String,
        write: Boolean,
        retry: Boolean,
        block: (SmbConnectionPool.Lease) -> R,
    ): R = withContext(dispatcherProvider.IO) {
        try {
            pool.use(path, retryOnTransportLoss = retry) { lease -> block(lease) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SmbStatusMapper.mapOperation(e, path, operation, write)
        }
    }

    override suspend fun lookup(path: SmbPath, options: LookupOptions): SmbPathLookup = try {
        read(path, "lookup") { lease ->
            val info = lease.share.getFileInformation(lease.smbPath(path))
            SmbPathLookup(
                lookedUp = path,
                fileType = fileType(info.basicInformation.fileAttributes),
                size = info.standardInformation.endOfFile,
                modifiedAt = info.basicInformation.lastWriteTime.toKotlinInstant(),
                createdAt = info.basicInformation.creationTime.toKotlinInstant(),
            )
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

    /** One QUERY_DIRECTORY round trip carries the metadata too, no lookup per child. */
    override suspend fun lookupFiles(path: SmbPath, options: LookupOptions): List<SmbPathLookup> =
        read(path, "lookupFiles") { lease ->
            lease.share.list(lease.smbPath(path))
                .filterNot { it.fileName == "." || it.fileName == ".." }
                .filter { SmbLocationInput.pathSegmentIssue(it.fileName) == null }
                .map { it.toLookup(path) }
        }

    override suspend fun exists(path: SmbPath): Boolean = try {
        read(path, "exists") { lease ->
            val smbPath = lease.smbPath(path)
            lease.share.fileExists(smbPath) || lease.share.folderExists(smbPath)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, VERBOSE) { "exists($path) -> false ($e)" }
        false
    }

    override suspend fun delete(path: SmbPath, recursive: Boolean): Boolean = mutate(path, "delete") { lease ->
        val smbPath = lease.smbPath(path)
        try {
            if (lease.share.folderExists(smbPath)) {
                lease.share.rmdir(smbPath, recursive)
            } else {
                lease.share.rm(smbPath)
            }
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
        mutate(path, "createDir") { lease -> lease.share.mkdir(lease.smbPath(path)) }
    }

    override suspend fun createFile(path: SmbPath, createParents: Boolean) {
        val parent = path.parent
        if (createParents && parent != null && parent.segments.isNotEmpty() && !exists(parent)) {
            createDir(parent, createParents = true)
        }
        mutate(path, "createFile") { lease ->
            lease.share.openFile(
                lease.smbPath(path),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            ).close()
        }
    }

    override suspend fun move(source: SmbPath, destination: SmbPath): MoveOutcome {
        if (source.locationId != destination.locationId) {
            return MoveOutcome.NotSupported("Source and destination are different network locations")
        }
        return mutate(source, "move") { lease ->
            lease.share.open(
                lease.smbPath(source),
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            ).use { entry ->
                entry.setFileInformation(FileRenameInformation(false, 0L, lease.smbPath(destination)))
            }
            MoveOutcome.Moved
        }
    }

    override suspend fun openInputStream(path: SmbPath): InputStream {
        val lease = pool.acquire(path.locationId)
        return try {
            val file = lease.share.openFile(
                lease.smbPath(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            )
            object : InputStream() {
                private val delegate = file.inputStream
                override fun read(): Int = delegate.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
                override fun available(): Int = delegate.available()
                override fun close() {
                    try {
                        delegate.close()
                        file.close()
                    } finally {
                        lease.close()
                    }
                }
            }
        } catch (e: Exception) {
            lease.close()
            throw SmbStatusMapper.mapOperation(e, path, "openInputStream", write = false)
        }
    }

    override suspend fun openOutputStream(path: SmbPath, append: Boolean): OutputStream {
        val lease = pool.acquire(path.locationId)
        return try {
            val file = lease.share.openFile(
                lease.smbPath(path),
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                if (append) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            )
            val delegate = file.getOutputStream(append)
            object : OutputStream() {
                override fun write(b: Int) = delegate.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
                override fun flush() = delegate.flush()
                override fun close() {
                    try {
                        delegate.close()
                        file.close()
                    } finally {
                        lease.close()
                    }
                }
            }
        } catch (e: Exception) {
            lease.close()
            throw SmbStatusMapper.mapOperation(e, path, "openOutputStream", write = true)
        }
    }

    override suspend fun file(path: SmbPath, readWrite: Boolean): FileHandle {
        val lease = pool.acquire(path.locationId)
        return try {
            val access = when {
                readWrite -> EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE)
                else -> EnumSet.of(AccessMask.GENERIC_READ)
            }
            val file = lease.share.openFile(
                lease.smbPath(path),
                access,
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                if (readWrite) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            )
            SmbFileHandle(readWrite, file, lease)
        } catch (e: Exception) {
            lease.close()
            throw SmbStatusMapper.mapOperation(e, path, "file", write = readWrite)
        }
    }

    override suspend fun setModifiedAt(path: SmbPath, modifiedAt: Instant): Boolean = try {
        mutate(path, "setModifiedAt") { lease ->
            lease.share.setFileInformation(
                lease.smbPath(path),
                FileBasicInformation(
                    FileBasicInformation.DONT_SET,
                    FileBasicInformation.DONT_SET,
                    FileTime.ofEpochMillis(modifiedAt.toEpochMilliseconds()),
                    FileBasicInformation.DONT_SET,
                    0L,
                ),
            )
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

    override suspend fun getFileSystem(path: SmbPath): FileSystem = try {
        read(path, "getFileSystem") { lease ->
            val info = lease.share.shareInformation
            FileSystem(freeSpace = info.freeSpace, totalSpace = info.totalSpace)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "getFileSystem($path) failed: ${e.asLog()}" }
        FileSystem()
    }

    private fun FileIdBothDirectoryInformation.toLookup(parent: SmbPath) = SmbPathLookup(
        lookedUp = parent.child(fileName),
        fileType = fileType(fileAttributes),
        size = endOfFile,
        modifiedAt = lastWriteTime.toKotlinInstant(),
        createdAt = creationTime.toKotlinInstant(),
    )

    private fun fileType(attributes: Long): FileType = when {
        attributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L -> FileType.SYMBOLIC_LINK
        attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L -> FileType.DIRECTORY
        else -> FileType.FILE
    }

    private fun FileTime.toKotlinInstant(): Instant = Instant.fromEpochMilliseconds(toEpochMillis())

    /**
     * The only place segments become an SMB path. Rejects separators and traversal segments here
     * too: a segment that slipped past [SmbPath]'s own check must not be able to address a
     * different directory on the server.
     */
    private fun SmbConnectionPool.Lease.smbPath(path: SmbPath): String = smbPath(location, path)

    companion object {
        val TAG = logTag("SMB", "FileSystemOps")

        fun smbPath(location: SmbLocation, path: SmbPath): String {
            val segments = location.basePath + path.segments
            segments.forEach { segment ->
                val issue = SmbLocationInput.pathSegmentIssue(segment)
                require(issue == null) { "Unusable SMB path segment '$segment' ($issue)" }
            }
            return segments.joinToString("\\")
        }
    }
}

private class SmbFileHandle(
    readWrite: Boolean,
    private val file: com.hierynomus.smbj.share.File,
    private val lease: SmbConnectionPool.Lease,
) : FileHandle(readWrite) {

    override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
        val read = file.read(array, fileOffset, arrayOffset, byteCount)
        return if (read == 0) -1 else read
    }

    override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
        var written = 0L
        while (written < byteCount) {
            val chunk = file.write(
                array,
                fileOffset + written,
                arrayOffset + written.toInt(),
                byteCount - written.toInt(),
            )
            if (chunk <= 0) throw WriteException("Server accepted 0 bytes")
            written += chunk
        }
    }

    override fun protectedSize(): Long = file.length

    override fun protectedResize(size: Long) {
        file.length = size
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

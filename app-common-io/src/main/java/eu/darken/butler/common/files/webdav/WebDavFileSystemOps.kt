package eu.darken.butler.common.files.webdav

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
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.webdav.WebDavDownload
import eu.darken.webdav.WebDavEntry
import eu.darken.webdav.WebDavException
import eu.darken.webdav.use
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileHandle
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * WebDAV gateway adapters backed by leases from [WebDavClientPool].
 *
 * Every upload declares its `Content-Length`. Content whose length is not known up front, a stream
 * from [openOutputStream] or a writable [file] handle, is spooled to the app cache first and sent
 * when it is closed or flushed.
 */
@Singleton
class WebDavFileSystemOps @Inject constructor(
    private val pool: WebDavClientPool,
    private val dispatcherProvider: DispatcherProvider,
    private val spool: WebDavSpool,
) : FileSystemOps<WebDavPath, WebDavPathLookup>, WebDavUploader {

    private suspend fun <R> read(
        path: WebDavPath,
        operation: String,
        block: suspend (WebDavClientPool.Lease) -> R,
    ): R = runOp(path, operation, write = false, retry = true, block)

    private suspend fun <R> mutate(
        path: WebDavPath,
        operation: String,
        block: suspend (WebDavClientPool.Lease) -> R,
    ): R = runOp(path, operation, write = true, retry = false, block)

    private suspend fun <R> runOp(
        path: WebDavPath,
        operation: String,
        write: Boolean,
        retry: Boolean,
        block: suspend (WebDavClientPool.Lease) -> R,
    ): R = withContext(dispatcherProvider.IO) {
        var lease: WebDavClientPool.Lease? = null
        try {
            pool.use(path, retryOnTransportLoss = retry) {
                lease = it
                block(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WebDavStatusMapper.mapOperation(e, path, operation, write, lease?.location)
        }
    }

    override suspend fun lookup(path: WebDavPath, options: LookupOptions): WebDavPathLookup = try {
        read(path, "lookup") { lease -> lease.session.stat(lease.serverPath(path)).toLookup(path) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "lookup($path) failed: ${e.asLog()}" }
        if (!options.fallbackToUnknown) throw e
        WebDavPathLookup(
            lookedUp = path,
            fileType = FileType.UNKNOWN,
            size = null,
            modifiedAt = null,
            error = e.message,
        )
    }

    override suspend fun listFiles(path: WebDavPath): List<WebDavPath> =
        lookupFiles(path, LookupOptions()).map { it.lookedUp }

    /** One `PROPFIND Depth: 1` answers the whole listing. */
    override suspend fun lookupFiles(path: WebDavPath, options: LookupOptions): List<WebDavPathLookup> =
        read(path, "lookupFiles") { lease ->
            lease.session.list(lease.serverPath(path)).toList().map { entry ->
                entry.toLookup(path.child(entry.path.segments.last()))
            }
        }

    override suspend fun exists(path: WebDavPath): Boolean = try {
        read(path, "exists") { lease ->
            lease.session.stat(lease.serverPath(path))
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, VERBOSE) { "exists($path) -> false ($e)" }
        false
    }

    /** A location URL the server answers with 404 is as definitive an absence as a missing path below it. */
    override suspend fun existsStrict(path: WebDavPath): Existence = try {
        read(path, "existsStrict") { lease ->
            lease.session.stat(lease.serverPath(path))
            Existence.PRESENT
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (WebDavStatusMapper.isMissing(e)) {
            Existence.ABSENT
        } else {
            log(TAG, WARN) { "existsStrict($path) could not be answered: ${e.asLog()}" }
            Existence.UNKNOWN
        }
    }

    /**
     * DELETE always takes a collection's content with it, so a non-recursive delete lists the
     * collection first. A member created between that listing and the DELETE is deleted too.
     */
    override suspend fun delete(path: WebDavPath, recursive: Boolean): Boolean = mutate(path, "delete") { lease ->
        val target = lease.serverPath(path)
        if (!recursive) {
            val member = try {
                lease.session.list(target).firstOrNull()
            } catch (e: WebDavException) {
                when (e.kind) {
                    WebDavException.Kind.MISSING -> return@mutate false
                    WebDavException.Kind.NOT_DIRECTORY -> null
                    else -> throw e
                }
            }
            if (member != null) throw WriteException("Directory is not empty", path)
        }
        try {
            lease.session.delete(target)
            true
        } catch (e: Exception) {
            if (WebDavStatusMapper.isMissing(e)) false else throw e
        }
    }

    /** With [createParents] an existing collection is fine, a file in the way is not. */
    override suspend fun createDir(path: WebDavPath, createParents: Boolean) {
        if (createParents) path.parent?.takeIf { it.segments.isNotEmpty() }?.let { ensureCollection(it) }
        try {
            mutate(path, "createDir") { lease -> lease.session.mkdir(lease.serverPath(path)) }
        } catch (e: PathAlreadyExistsException) {
            if (!createParents || statOrNull(path)?.fileType != FileType.DIRECTORY) throw e
        }
    }

    /** `If-None-Match: *` makes the server refuse to replace an existing file, without a race. */
    override suspend fun createFile(path: WebDavPath, createParents: Boolean) {
        if (createParents) path.parent?.takeIf { it.segments.isNotEmpty() }?.let { ensureCollection(it) }
        try {
            mutate(path, "createFile") { lease ->
                lease.session.openWrite(lease.serverPath(path), contentLength = 0, createOnly = true).use { }
            }
        } catch (e: PathAlreadyExistsException) {
            throw e
        } catch (e: IOException) {
            // A PUT onto a collection is refused as an unsupported method rather than as a conflict.
            if (WebDavStatusMapper.kindOf(e) == WebDavException.Kind.UNSUPPORTED &&
                statOrNull(path)?.fileType == FileType.DIRECTORY
            ) {
                throw PathAlreadyExistsException(path = path, cause = e)
            }
            throw e
        }
    }

    private suspend fun ensureCollection(path: WebDavPath) {
        val existing = statOrNull(path)
        when {
            existing == null -> createDir(path, createParents = true)
            existing.fileType != FileType.DIRECTORY -> throw PathAlreadyExistsException(path = path)
        }
    }

    /** Null only when the server says the path does not exist. */
    private suspend fun statOrNull(path: WebDavPath): WebDavPathLookup? = try {
        read(path, "lookup") { lease -> lease.session.stat(lease.serverPath(path)).toLookup(path) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (WebDavStatusMapper.isMissing(e)) null else throw e
    }

    /** A server-side MOVE, which never replaces an existing destination. */
    override suspend fun move(source: WebDavPath, destination: WebDavPath): MoveOutcome {
        if (source.locationId != destination.locationId) {
            return MoveOutcome.NotSupported("Source and destination are different network locations")
        }
        requireAbsent(destination)
        return mutate(source, "move") { lease ->
            transfer(destination) { lease.session.move(lease.serverPath(source), lease.serverPath(destination)) }
            MoveOutcome.Moved
        }
    }

    /** A server-side COPY within one location, collections included; never replaces an existing destination. */
    suspend fun copy(source: WebDavPath, destination: WebDavPath) {
        require(source.locationId == destination.locationId) { "Server-side copies stay on one location" }
        requireAbsent(destination)
        mutate(source, "copy") { lease ->
            transfer(destination) { lease.session.copy(lease.serverPath(source), lease.serverPath(destination)) }
        }
    }

    /**
     * MOVE and COPY send `Overwrite: F`, yet stock Apache answers one whose destination is an existing
     * collection named without a trailing slash with a 301 that has no `Location`, not with a 412.
     */
    private suspend fun requireAbsent(destination: WebDavPath) {
        if (statOrNull(destination) != null) throw PathAlreadyExistsException(path = destination)
    }

    private suspend fun transfer(destination: WebDavPath, block: suspend () -> Unit) = try {
        block()
    } catch (e: WebDavException) {
        if (e.kind == WebDavException.Kind.ALREADY_EXISTS) throw PathAlreadyExistsException(path = destination, cause = e)
        throw e
    }

    override suspend fun upload(
        path: WebDavPath,
        length: Long,
        input: InputStream,
        onProgress: suspend (Long) -> Unit,
    ) = mutate(path, "upload") { lease -> lease.upload(path, length, input, onProgress) }

    private suspend fun WebDavClientPool.Lease.upload(
        path: WebDavPath,
        length: Long,
        input: InputStream,
        onProgress: suspend (Long) -> Unit,
    ) {
        session.openWrite(serverPath(path), contentLength = length).use { upload ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                upload.write(buffer, 0, read)
                onProgress(read.toLong())
            }
        }
    }

    /** Uploads a closed spool file, mapping a failure to a write failure of [path]. */
    private fun WebDavClientPool.Lease.uploadSpool(path: WebDavPath, file: File) = runBlocking {
        try {
            file.inputStream().use { this@uploadSpool.upload(path, file.length(), it) {} }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WebDavStatusMapper.mapOperation(e, path, "upload", write = true, location = location)
        }
    }

    /** Resources are discarded rather than closed when the caller never received them. */
    private interface Discardable {
        fun discard()
    }

    private suspend fun <T : java.io.Closeable> openResource(
        path: WebDavPath,
        operation: String,
        write: Boolean,
        block: suspend (WebDavClientPool.Lease) -> T,
    ): T {
        var lease: WebDavClientPool.Lease? = null
        var resource: T? = null
        try {
            return withContext(dispatcherProvider.IO) {
                lease = pool.acquire(path.locationId)
                block(lease).also { resource = it }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + dispatcherProvider.IO) {
                runCatching {
                    when (val abandoned = resource) {
                        is Discardable -> abandoned.discard()
                        else -> abandoned?.close()
                    }
                }
                    .exceptionOrNull()
                    ?.let { if (it !== error) error.addSuppressed(it) }
                lease?.close()
            }
            if (error is CancellationException) throw error
            throw WebDavStatusMapper.mapOperation(error, path, operation, write, lease?.location)
        }
    }

    override suspend fun openInputStream(path: WebDavPath): InputStream =
        openResource(path, "openInputStream", false) { lease ->
            val download = lease.session.openRead(lease.serverPath(path))
            val body = download.blocking()
            val stream = object : InputStream() {
                override fun read(): Int =
                    ByteArray(1).let { if (read(it, 0, 1) == -1) -1 else it[0].toInt() and 255 }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = try {
                    body.read(bytes, offset, length)
                } catch (error: Exception) {
                    throw WebDavStatusMapper.mapOperation(error, path, "read", false, lease.location)
                }

                override fun close() {
                    try {
                        download.closeBlocking()
                    } finally {
                        lease.close()
                    }
                }
            }
            BufferedInputStream(stream, 256 * 1024)
        }

    /**
     * Without [append] the content is spooled and uploaded on [OutputStream.close], which throws if
     * the upload fails. With [append] the existing content is downloaded into the spool first.
     */
    override suspend fun openOutputStream(path: WebDavPath, append: Boolean): OutputStream =
        openResource(path, "openOutputStream", true) { lease ->
            if (append) {
                val handle = SpoolFileHandle(lease, path, spool.create())
                val start = try {
                    handle.size()
                } catch (error: Throwable) {
                    handle.discard()
                    throw error
                }
                AppendingStream(handle, start)
            } else {
                SpoolStream(lease, path, spool.create())
            }
        }

    /**
     * Read-only handles read the file with ranged GETs, writable ones work on a spooled copy.
     *
     * A read-only handle is pinned to the version it opened: its size is the size at open, and a
     * seek to another offset only continues on a file whose ETag still matches. Without an ETag, or on
     * a server that ignores ranges, a seek re-reads the file from the start and skips forward.
     */
    override suspend fun file(path: WebDavPath, readWrite: Boolean): FileHandle =
        openResource(path, "file", readWrite) { lease ->
            if (readWrite) {
                SpoolFileHandle(lease, path, spool.create())
            } else {
                val first = lease.session.openRead(lease.serverPath(path))
                val size = try {
                    first.totalLength ?: lease.session.stat(lease.serverPath(path)).size
                        ?: throw ReadException("The server does not report the size of the file", path)
                } catch (error: Throwable) {
                    first.closeBlocking()
                    throw error
                }
                RangeReadHandle(lease, path, first, size)
            }
        }

    override suspend fun setModifiedAt(path: WebDavPath, modifiedAt: Instant): Boolean = false

    override suspend fun setPermissions(path: WebDavPath, permissions: Permissions): Boolean = false

    override suspend fun setOwnership(path: WebDavPath, ownership: Ownership): Boolean = false

    override suspend fun createSymlink(linkPath: WebDavPath, targetPath: WebDavPath): Boolean {
        throw UnsupportedOperationException("WebDAV does not support symlinks")
    }

    override suspend fun readSymbolicLink(linkPath: WebDavPath): WebDavPath {
        throw UnsupportedOperationException("WebDAV does not support symlinks")
    }

    override suspend fun canonicalize(path: WebDavPath): WebDavPath = path

    // The server enforces access, and probing it costs a round trip per item during listings.
    override suspend fun canRead(path: WebDavPath): Boolean = true

    override suspend fun canWrite(path: WebDavPath): Boolean = true

    /** RFC 4331 quota of [path]; empty when the server reports none or an unlimited one. */
    override suspend fun getFileSystem(path: WebDavPath): FileSystem = try {
        read(path, "getFileSystem") { lease ->
            val capacity = lease.session.capacity(lease.serverPath(path))
            FileSystem(freeSpace = capacity?.availableBytes, totalSpace = capacity?.totalBytes)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "getFileSystem($path) failed: ${e.asLog()}" }
        FileSystem()
    }

    private fun WebDavEntry.toLookup(path: WebDavPath) = WebDavPathLookup(
        lookedUp = path,
        fileType = if (isCollection) FileType.DIRECTORY else FileType.FILE,
        size = size,
        modifiedAt = modifiedAt,
        etag = etag,
        contentType = contentType,
    )

    private fun WebDavDownload.closeBlocking() = runBlocking { close() }

    /** Spools everything written and uploads it with its length once closed. */
    private inner class SpoolStream(
        private val lease: WebDavClientPool.Lease,
        private val path: WebDavPath,
        private val file: File,
    ) : OutputStream(), Discardable {
        private val out = try {
            BufferedOutputStream(FileOutputStream(file), BUFFER_SIZE)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        private var closed = false

        override fun write(value: Int) = out.write(value)

        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)

        override fun flush() = out.flush()

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            try {
                out.close()
                lease.uploadSpool(path, file)
            } finally {
                file.delete()
                lease.close()
            }
        }

        @Synchronized
        override fun discard() {
            if (closed) return
            closed = true
            try {
                runCatching { out.close() }
            } finally {
                file.delete()
                lease.close()
            }
        }
    }

    private class AppendingStream(
        private val handle: SpoolFileHandle,
        private var position: Long,
    ) : OutputStream(), Discardable {

        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            handle.write(position, bytes, offset, length)
            position += length
        }

        override fun close() = handle.close()

        override fun discard() = handle.discard()
    }

    /**
     * A writable handle on a local copy. The existing content is downloaded on the first read, write,
     * size query or resize to a non-zero length; a [resize] to 0 before any of those skips the download.
     * A missing file starts out empty. [flush] and [close] upload a changed copy with its length.
     */
    private inner class SpoolFileHandle(
        private val lease: WebDavClientPool.Lease,
        private val path: WebDavPath,
        private val file: File,
    ) : FileHandle(readWrite = true), Discardable {

        private val raf = try {
            RandomAccessFile(file, "rw")
        } catch (error: Throwable) {
            file.delete()
            lease.close()
            throw error
        }
        private val handleLock = Any()
        private var loaded = false
        private var dirty = false
        private var released = false
        private var uploadFailure: Throwable? = null

        private fun ensureLoaded() {
            if (loaded) return
            raf.setLength(0)
            try {
                runBlocking {
                    lease.session.openRead(lease.serverPath(path)).use { download ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        val body = download.blocking()
                        while (true) {
                            val read = body.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                raf.setLength(0)
                if (!WebDavStatusMapper.isMissing(e)) {
                    throw WebDavStatusMapper.mapOperation(e, path, "read", false, lease.location)
                }
            }
            loaded = true
        }

        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
            synchronized(handleLock) {
                ensureLoaded()
                if (byteCount == 0) return 0
                raf.seek(fileOffset)
                raf.read(array, arrayOffset, byteCount)
            }

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            synchronized(handleLock) {
                ensureLoaded()
                raf.seek(fileOffset)
                raf.write(array, arrayOffset, byteCount)
                dirty = true
            }

        override fun protectedSize(): Long = synchronized(handleLock) {
            ensureLoaded()
            raf.length()
        }

        override fun protectedResize(size: Long) = synchronized(handleLock) {
            if (size == 0L && !loaded) {
                loaded = true
            } else {
                ensureLoaded()
            }
            raf.setLength(size)
            dirty = true
        }

        override fun protectedFlush() {
            synchronized(handleLock) {
                uploadFailure?.let { throw it }
                if (!dirty) return
                upload()
                dirty = false
            }
        }

        override fun protectedClose() = synchronized(handleLock) {
            try {
                uploadFailure?.let { throw it }
                if (dirty) upload()
            } finally {
                release()
            }
        }

        /** A failed upload is final for this handle: neither [flush] nor [close] sends it again. */
        private fun upload() {
            try {
                lease.uploadSpool(path, file)
            } catch (error: Throwable) {
                uploadFailure = error
                throw error
            }
        }

        override fun discard() = synchronized(handleLock) { release() }

        private fun release() {
            if (released) return
            released = true
            try {
                raf.close()
            } finally {
                file.delete()
                lease.close()
            }
        }
    }

    private inner class RangeReadHandle(
        private val lease: WebDavClientPool.Lease,
        private val path: WebDavPath,
        first: WebDavDownload,
        private val size: Long,
    ) : FileHandle(readWrite = false) {

        private val etag = first.etag
        private val handleLock = Any()
        private var current: WebDavDownload? = first
        private var position = 0L

        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
            synchronized(handleLock) {
                if (byteCount == 0) return 0
                if (fileOffset >= size) return -1
                try {
                    if (!seek(fileOffset)) return -1
                    val read = current!!.blocking().read(array, arrayOffset, byteCount)
                    if (read > 0) position += read
                    read
                } catch (error: Exception) {
                    drop()
                    throw WebDavStatusMapper.mapOperation(error, path, "read", false, lease.location)
                }
            }

        /** @return false if the file ended before [offset] */
        private fun seek(offset: Long): Boolean {
            val open = current
            if (open != null) {
                if (offset == position) return true
                // Skipping ahead on the open response beats a new request for a short gap, and is the
                // only way forward without an ETag to pin a new one to.
                if (offset > position && (etag == null || offset - position <= SKIP_LIMIT)) {
                    return skip(open, offset)
                }
            }
            drop()
            val download = runBlocking {
                if (etag != null) {
                    lease.session.openRead(lease.serverPath(path), offset, expectedEtag = etag)
                } else {
                    lease.session.openRead(lease.serverPath(path))
                }
            }
            current = download
            position = if (etag != null && download.rangeHonored) offset else 0L
            return skip(download, offset)
        }

        private fun skip(download: WebDavDownload, offset: Long): Boolean {
            val body = download.blocking()
            val scratch = ByteArray(minOf(BUFFER_SIZE.toLong(), offset - position).toInt().coerceAtLeast(1))
            while (position < offset) {
                val read = body.read(scratch, 0, minOf(scratch.size.toLong(), offset - position).toInt())
                if (read == -1) return false
                position += read
            }
            return true
        }

        private fun drop() {
            val open = current ?: return
            current = null
            runCatching { open.closeBlocking() }
        }

        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw UnsupportedOperationException("Read-only handle")

        override fun protectedSize(): Long = size

        override fun protectedResize(size: Long) = throw UnsupportedOperationException("Read-only handle")

        override fun protectedFlush() = Unit

        override fun protectedClose() = synchronized(handleLock) {
            try {
                drop()
            } finally {
                lease.close()
            }
        }
    }

    companion object {
        val TAG = logTag("WebDAV", "FileSystemOps")

        private const val BUFFER_SIZE = 64 * 1024
        private const val SKIP_LIMIT = 256L * 1024
    }
}

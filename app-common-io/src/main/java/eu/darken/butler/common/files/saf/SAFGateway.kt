package eu.darken.butler.common.files.saf

import android.content.Intent
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.isFile
import eu.darken.butler.common.files.metadata.FileSystemInfo
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.sharedresource.SharedResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.FileHandle
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

@Singleton
class SAFGateway @Inject constructor(
    private val fileSystemOps: SAFFileSystemOps,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
) : APathGateway<SAFPath, SAFPathLookup, SAFPathLookupExtended> {

    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    private suspend fun <T> runIO(
        block: suspend CoroutineScope.() -> T
    ): T = withContext(dispatcherProvider.IO) { block() }

    override suspend fun createFile(path: SAFPath): Unit = runIO {
        fileSystemOps.createFile(path)
    }

    override suspend fun createDir(path: SAFPath): Unit = runIO {
        fileSystemOps.createDir(path)
    }

    override suspend fun listFiles(path: SAFPath): List<SAFPath> = runIO {
        fileSystemOps.listFiles(path)
    }

    override suspend fun exists(path: SAFPath): Boolean = runIO {
        fileSystemOps.exists(path)
    }

    override suspend fun canWrite(path: SAFPath): Boolean = runIO {
        fileSystemOps.canWrite(path)
    }

    override suspend fun canRead(path: SAFPath): Boolean = runIO {
        fileSystemOps.canRead(path)
    }

    override suspend fun delete(path: SAFPath): Boolean = runIO {
        fileSystemOps.delete(path)
    }

    override suspend fun openInputStream(path: SAFPath): InputStream = runIO {
        fileSystemOps.openInputStream(path)
    }

    override suspend fun openOutputStream(path: SAFPath, append: Boolean): OutputStream = runIO {
        fileSystemOps.openOutputStream(path, append)
    }

    override suspend fun lookup(path: SAFPath): SAFPathLookup = runIO {
        fileSystemOps.lookup(path).also {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "Looked up: $it" }
        }
    }

    override suspend fun lookupExtended(path: SAFPath): SAFPathLookupExtended = runIO {
        fileSystemOps.lookupExtended(path).also {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "Looked up extended: $it" }
        }
    }

    override suspend fun lookupFiles(path: SAFPath): List<SAFPathLookup> = runIO {
        try {
            log(TAG, VERBOSE) { "lookupFiles($path)" }
            listFiles(path)
                .map { lookup(it) }
                .also {
                    if (Bugs.isTrace) {
                        log(TAG, VERBOSE) { "Looked up ${it.size} items:" }
                        it.forEachIndexed { index, look -> log(TAG, VERBOSE) { "#$index $look" } }
                    }
                }
        } catch (e: Exception) {
            log(TAG, WARN) { "lookupFiles($path) failed." }
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun lookupFilesExtended(path: SAFPath): List<SAFPathLookupExtended> = runIO {
        try {
            log(TAG, VERBOSE) { "lookupFilesExtended($path)" }
            listFiles(path)
                .map { lookupExtended(it) }
                .also {
                    if (Bugs.isTrace) {
                        log(TAG, VERBOSE) { "Looked up ${it.size} items:" }
                        it.forEachIndexed { index, look -> log(TAG, VERBOSE) { "#$index $look" } }
                    }
                }
        } catch (e: Exception) {
            log(TAG, WARN) { "lookupFilesExtended($path) failed." }
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun walk(
        path: SAFPath,
        options: APathGateway.WalkOptions<SAFPath, SAFPathLookup>,
    ): Flow<SAFPathLookup> = flow {
        val start = lookup(path)
        log(TAG, VERBOSE) { "walk($path) -> $start" }

        if (start.isFile) {
            emit(start)
            return@flow
        }

        val queue = LinkedList(listOf(start))

        while (!queue.isEmpty()) {
            val lookUp = queue.removeFirst()

            val newBatch = try {
                lookupFiles(lookUp.lookedUp)
            } catch (e: IOException) {
                log(TAG, ERROR) { "Failed to read $lookUp: $e" }
                if (options.onError?.invoke(lookUp, e) != false) {
                    emptyList()
                } else {
                    throw e
                }
            }

            newBatch
                .filter {
                    val allowed = options.onFilter?.invoke(it) ?: true
                    if (Bugs.isTrace) {
                        if (!allowed) log(TAG, VERBOSE) { "Skipping (filter): $it" }
                    }
                    allowed
                }
                .forEach { child ->
                    if (child.isDirectory) {
                        if (Bugs.isTrace) log(TAG, VERBOSE) { "Walking: $child" }
                        queue.addFirst(child)
                    }
                    emit(child)
                }
        }
    }
        .flowOn(dispatcherProvider.IO)
        .catch { e ->
            log(TAG, WARN) { "walk($path) failed." }
            throw ReadException(path = path, cause = e)
        }


    override suspend fun du(
        path: SAFPath,
        options: APathGateway.DuOptions<SAFPath, SAFPathLookup>,
    ): Long = runIO {
        try {
            val start = lookup(path)
            log(TAG, VERBOSE) { "du($path) -> $start" }

            if (start.isFile) return@runIO start.size

            var total = start.size

            val queue = LinkedList(listOf(start))
            while (!queue.isEmpty()) {
                val lookUp = queue.removeFirst()

                val newBatch = try {
                    lookupFiles(lookUp.lookedUp)
                } catch (e: IOException) {
                    log(TAG, ERROR) { "Failed to read $lookUp: $e" }
                    emptyList()
                }

                newBatch.forEach { child ->
                    total += child.size
                    if (child.isDirectory) queue.addFirst(child)
                }
            }

            total
        } catch (e: Exception) {
            log(TAG, WARN) { "du($path) failed." }
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun file(path: SAFPath, readWrite: Boolean): FileHandle = runIO {
        try {
            val lookup = fileSystemOps.lookup(path)
            log(TAG, VERBOSE) { "file(readWrite=$readWrite): $path" }

            if (readWrite && !lookup.docFile.writable) throw IOException("writable=false")
            else if (!lookup.docFile.readable) throw IOException("readable=false")

            val pfd = lookup.docFile.openPFD(if (readWrite) FileMode.READ_WRITE else FileMode.READ)
            pfd.toFileHandle(readWrite)
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to access from $path: ${e.asLog()}" }
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun setModifiedAt(path: SAFPath, modifiedAt: Instant): Boolean = runIO {
        fileSystemOps.setModifiedAt(path, modifiedAt)
    }

    override suspend fun setPermissions(path: SAFPath, permissions: Permissions): Boolean = runIO {
        fileSystemOps.setPermissions(path, permissions)
    }

    override suspend fun setOwnership(path: SAFPath, ownership: Ownership): Boolean = runIO {
        fileSystemOps.setOwnership(path, ownership)
    }

    override suspend fun createSymlink(linkPath: SAFPath, targetPath: SAFPath): Boolean = runIO {
        fileSystemOps.createSymlink(linkPath, targetPath)
    }

    override suspend fun getInfo(path: SAFPath): FileSystemInfo {
        TODO("Not yet implemented")
    }

    override suspend fun delete(
        targets: Set<SAFPath>,
        options: DeleteAction.Options<SAFPath>
    ): Flow<DeleteAction.State<SAFPath, SAFPathLookup>> {
        TODO("Not yet implemented")
//        log(TAG, VERBOSE) { "delete(recursive=$recursive): $path" }
//
//        val queue = LinkedList(listOf(lookup(path)))
//
//        while (!queue.isEmpty()) {
//            val lookUp = queue.removeFirst()
//
//            if (lookUp.isDirectory) {
//                val newBatch = try {
//                    lookupFiles(lookUp.lookedUp)
//                } catch (e: IOException) {
//                    log(TAG, ERROR) { "Failed to read directory to delete $lookUp: $e" }
//                    throw ReadException(path = path, cause = e)
//                }
//                queue.addAll(newBatch)
//            } else {
//                var success = try {
//                    lookUp.docFile.delete()
//                } catch (e: Exception) {
//                    throw WriteException(path = path, cause = e)
//                }
//
//                if (!success) {
//                    success = try {
//                        !lookUp.docFile.exists
//                    } catch (e: IOException) {
//                        log(TAG, ERROR) { "Failed to perform exists() check $lookUp: $e" }
//                        throw ReadException(path = path, cause = e)
//                    }
//                    if (success) log(TAG, WARN) { "Tried to delete file, but it's already gone: $path" }
//                }
//
//                if (!success) throw IOException("Document delete() call returned false")
//            }
//        }
    }

    override suspend fun copy(
        sources: Set<SAFPath>,
        destination: SAFPath,
        options: CopyAction.Options<SAFPath>
    ): Flow<CopyAction.State<SAFPath, SAFPathLookup>> {
        // TODO: Implement using DocumentFile APIs
        // - Use DocumentFile.listFiles() for traversal across all sources
        // - Use ContentResolver streams for copying
        // - Handle issues via options.onIssue callback
        // - Report cumulative progress across all sources
        // - Support "Apply to All" via gateway-level state management
        throw NotImplementedError("TODO: SAFGateway multi-source copy implementation")
    }

    override suspend fun move(
        sources: Set<SAFPath>,
        destination: SAFPath,
        options: MoveAction.Options<SAFPath>
    ): Flow<MoveAction.State<SAFPath, SAFPathLookup>> {
        // TODO: Implement using DocumentFile.renameTo()
        // - Try renameTo() for same parent directory across all sources
        // - Fallback to copy+delete for different parents
        // - Handle issues via options.onIssue callback
        // - Report cumulative progress across all sources
        // - Support "Apply to All" via gateway-level state management
        throw NotImplementedError("TODO: SAFGateway multi-source move implementation")
    }

    companion object {
        val TAG = logTag("Gateway", "SAF")

        const val RW_FLAGSINT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
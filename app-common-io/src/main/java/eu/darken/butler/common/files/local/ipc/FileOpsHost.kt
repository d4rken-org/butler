package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import eu.darken.butler.common.files.local.walkers.DirectLocalWalker
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.ipc.IpcHostModule
import eu.darken.butler.common.ipc.RemoteFileHandle
import eu.darken.butler.common.ipc.RemoteInputStream
import eu.darken.butler.common.ipc.remoteFileHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import kotlin.time.Instant

/**
 * ROOT-side
 */
class FileOpsHost @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val fileSystemOps: LocalFileSystemOps,
) : FileOpsConnection.Stub(), IpcHostModule {

    override fun listFilesStream(path: LocalPath): RemoteInputStream = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "listFilesStream($path)..." }
        val result = runBlocking { fileSystemOps.listFiles(path) }
        if (Bugs.isTrace) log(TAG, VERBOSE) { "listFilesStream($path) ${result.size} items read, now streaming" }
        result.toRemoteInputStream()
    } catch (e: Exception) {
        log(TAG, ERROR) { "lookupFiles(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun lookup(path: LocalPath): LocalPathLookup = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "lookup($path)..." }
        runBlocking { fileSystemOps.lookup(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "lookup(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun lookupFilesStream(path: LocalPath): RemoteInputStream = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "lookupFilesStream($path)..." }
        val lookups = runBlocking { fileSystemOps.lookupFiles(path) }
        lookups.toRemoteInputStream()
    } catch (e: Exception) {
        log(TAG, ERROR) { "lookupFiles(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun lookUpExtended(path: LocalPath): LocalPathLookupExtended = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "lookUpExtended($path)..." }
        runBlocking { fileSystemOps.lookupExtended(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "lookUpExtended(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun lookupFilesExtended(path: LocalPath): List<LocalPathLookupExtended> = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "lookupFilesExtended($path)..." }
        runBlocking { fileSystemOps.lookupFilesExtended(path) }.also {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "lookupFilesExtended($path) done: ${it.size} items" }
        }
    } catch (e: Exception) {
        log(TAG, ERROR) { "lookupFilesExtended(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun walkStream(path: LocalPath, pathDoesNotContain: List<String>): RemoteInputStream = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "walkStream($path)..." }
        runBlocking {
            DirectLocalWalker(
                fileSystemOps = fileSystemOps,
                start = path,
                onFilter = { lookup ->
                    pathDoesNotContain.none { lookup.path.contains(it) }
                },
            )
        }.toRemoteInputStream(appScope + dispatcherProvider.IO)
    } catch (e: Exception) {
        log(TAG, ERROR) { "walkStream(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun lookupFilesExtendedStream(path: LocalPath): RemoteInputStream = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "lookupFilesExtendedStream($path)..." }
        val lookups = runBlocking { fileSystemOps.lookupFilesExtended(path) }
        lookups.toRemoteInputStream()
    } catch (e: Exception) {
        log(TAG, ERROR) { "lookupFilesExtendedStream(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun du(path: LocalPath): Long = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "du($path)..." }
        runBlocking { fileSystemOps.du(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "exists(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun file(path: LocalPath, readWrite: Boolean): RemoteFileHandle = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "file($path, $readWrite)..." }
        runBlocking { fileSystemOps.file(path, readWrite) }.remoteFileHandle()
    } catch (e: Exception) {
        log(TAG, ERROR) { "file(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun createDir(path: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "createDir($path)..." }
        runBlocking { fileSystemOps.createDir(path) }
        true
    } catch (e: Exception) {
        log(TAG, ERROR) { "createDir(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun createFile(path: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "createFile($path)..." }
        runBlocking { fileSystemOps.createFile(path) }
        true
    } catch (e: Exception) {
        log(TAG, ERROR) { "createFile(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun canRead(path: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "canRead($path)..." }
        runBlocking { fileSystemOps.canRead(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "path(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun canWrite(path: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "canWrite($path)..." }
        runBlocking { fileSystemOps.canWrite(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "canWrite(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun exists(path: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "exists($path)..." }
        runBlocking { fileSystemOps.exists(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "exists(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun delete(path: LocalPath): Boolean = try {
        log(TAG, VERBOSE) { "delete($path)..." }
        runBlocking { fileSystemOps.delete(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "delete(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun createSymlink(linkPath: LocalPath, targetPath: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "createSymlink($linkPath,$targetPath)..." }
        runBlocking { fileSystemOps.createSymlink(linkPath, targetPath) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "createSymlink(linkPath=$linkPath, targetPath=$targetPath) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun setModifiedAt(path: LocalPath, modifiedAt: Long): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "setModifiedAt($path,$modifiedAt)..." }
        runBlocking { fileSystemOps.setModifiedAt(path, Instant.fromEpochMilliseconds(modifiedAt)) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "setModifiedAt(path=$path, modifiedAt=$modifiedAt) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun setPermissions(path: LocalPath, permissions: Permissions): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "setPermissions($path,$permissions)..." }
        runBlocking { fileSystemOps.setPermissions(path, permissions) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "setModifiedAt(path=$path, permissions=$permissions) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun setOwnership(path: LocalPath, ownership: Ownership): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "setPermissions($path,$ownership)..." }
        runBlocking { fileSystemOps.setOwnership(path, ownership) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "setModifiedAt(path=$path, ownership=$ownership) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    companion object {
        val TAG = logTag("FileOps", "Service", "Host", Bugs.processTag)
    }
}
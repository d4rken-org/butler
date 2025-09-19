package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.operations.DeleteOperation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import okio.FileHandle
import okio.IOException
import kotlin.time.Instant

suspend fun <P : APath, PL : APathLookup<P>, PLE : APathLookupExtended<P>, GT : APathGateway<P, PL, PLE>> P.walk(
    gateway: GT,
    options: APathGateway.WalkOptions<P, PL> = APathGateway.WalkOptions()
): Flow<PL> {
    return gateway.walk(this, options)
}

suspend fun <P : APath, PL : APathLookup<P>, PLE : APathLookupExtended<P>, GT : APathGateway<P, PL, PLE>> P.du(
    gateway: GT,
    options: APathGateway.DuOptions<P, PL> = APathGateway.DuOptions()
): Long {
    return gateway.du(this, options)
}

suspend fun <T : APath> T.exists(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.exists(this)
}

suspend fun <T : APath> T.requireExists(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
    if (!exists(gateway)) {
        throw IllegalStateException("Path doesn't exist, but should: $this")
    }
    return this
}

suspend fun <T : APath> T.requireNotExists(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
    if (exists(gateway)) {
        throw IllegalStateException("Path exist, but shouldn't: $this")
    }
    return this
}

suspend fun <T : APath> T.createFileIfNecessary(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
    if (exists(gateway)) {
        if (gateway.lookup(this).fileType == FileType.FILE) {
            log(VERBOSE) { "File already exists, not creating: $this" }
            return this
        } else {
            throw IOException("Exists, but is not a file: $this")
        }
    }

    return createFile(gateway)
}

suspend fun <T : APath> T.createFile(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
    gateway.createFile(this)
    log(VERBOSE) { "File created: $this" }
    return this
}

suspend fun <T : APath> T.createDirIfNecessary(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
    if (exists(gateway)) {
        if (gateway.lookup(this).isDirectory) {
            log(VERBOSE) { "Directory already exists, not creating: $this" }
            return this
        } else {
            throw IOException("Exists, but is not a directory: $this")
        }
    }

    gateway.createDir(this)
    log(VERBOSE) { "Directory created: $this" }
    return this
}

suspend fun <T : APath> T.delete(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    options: DeleteOperation.Options<T>,
) = setOf(this).delete(gateway, options)

suspend fun <T : APath> Collection<T>.delete(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    options: DeleteOperation.Options<T>,
): Flow<DeleteOperation.Result<T>> {
    return gateway
        .delete(targets = this.toSet(), options = options)
        .onCompletion {
            log(VERBOSE) { "Collection<APath>.delete(options=$options): Deleted $this@delete" }
        }
}

suspend fun <T : APath> T.file(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    readWrite: Boolean,
): FileHandle {
    return gateway.file(this, readWrite)
}

suspend fun <T : APath> T.createSymlink(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    target: T
): Boolean {
    return gateway.createSymlink(this, target)
}

suspend fun <T : APath> T.setModifiedAt(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    modifiedAt: Instant
): Boolean {
    return gateway.setModifiedAt(this, modifiedAt)
}

suspend fun <T : APath> T.setPermissions(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    permissions: Permissions
): Boolean {
    return gateway.setPermissions(this, permissions)
}

suspend fun <T : APath> T.setOwnership(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    ownership: Ownership
): Boolean {
    return gateway.setOwnership(this, ownership)
}

suspend fun <P : APath, PL : APathLookup<P>, PLE : APathLookupExtended<P>> P.lookup(gateway: APathGateway<P, PL, PLE>): PL {
    return gateway.lookup(this)
}

suspend fun <P : APath, PL : APathLookup<P>, PLE : APathLookupExtended<P>> P.lookupFiles(gateway: APathGateway<P, PL, PLE>): Collection<PL> {
    return gateway.lookupFiles(this)
}

suspend fun <P : APath, PL : APathLookup<P>, PLE : APathLookupExtended<P>> P.lookupFilesOrNull(gateway: APathGateway<P, PL, PLE>): Collection<PL>? {
    return if (exists(gateway)) gateway.lookupFiles(this) else null
}

suspend fun <T : APath> T.listFiles(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Collection<T> {
    return gateway.listFiles(this)
}

suspend fun <T : APath> T.canRead(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.canRead(this)
}

suspend fun <T : APath> T.canWrite(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.canWrite(this)
}

suspend fun <T : APath> T.isFile(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.lookup(this).fileType == FileType.FILE
}

suspend fun <T : APath> T.isDirectory(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.lookup(this).fileType == FileType.DIRECTORY
}
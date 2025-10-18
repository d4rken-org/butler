package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import okio.FileHandle
import okio.IOException
import kotlin.time.Instant

suspend fun <P : APath<P>, PL : APathLookup<P>, PLE : APathLookupExtended<P>, GT : APathGateway<P, PL, PLE>> P.walk(
    gateway: GT,
    options: APathGateway.WalkOptions<P, PL> = APathGateway.WalkOptions()
): Flow<PL> {
    return gateway.walk(this, options)
}

suspend fun <P : APath<P>, PL : APathLookup<P>, PLE : APathLookupExtended<P>, GT : APathGateway<P, PL, PLE>> P.du(
    gateway: GT,
    options: APathGateway.DuOptions<P, PL> = APathGateway.DuOptions()
): Long {
    return gateway.du(this, options)
}

suspend fun <T : APath<T>> T.exists(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.exists(this)
}

suspend fun <T : APath<T>> T.requireExists(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
    if (!exists(gateway)) {
        throw IllegalStateException("Path doesn't exist, but should: $this")
    }
    return this
}

suspend fun <T : APath<T>> T.requireNotExists(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
    if (exists(gateway)) {
        throw IllegalStateException("Path exist, but shouldn't: $this")
    }
    return this
}

suspend fun <T : APath<T>> T.createFileIfNecessary(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
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

suspend fun <T : APath<T>> T.createFile(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
    gateway.createFile(this)
    log(VERBOSE) { "File created: $this" }
    return this
}

suspend fun <T : APath<T>> T.createDirIfNecessary(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): T {
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

suspend fun <P : APath<P>, PL : APathLookup<P>> P.delete(
    gateway: APathGateway<P, PL, out APathLookupExtended<P>>,
    options: DeleteAction.Options<P>,
) = setOf(this).delete(gateway, options)

suspend fun <P : APath<P>, PL : APathLookup<P>> Collection<P>.delete(
    gateway: APathGateway<P, PL, out APathLookupExtended<P>>,
    options: DeleteAction.Options<P>,
): Flow<DeleteAction.State<P, PL>> {
    val targets = this@delete.toSet()
    return gateway
        .delete(targets = targets, options = options)
        .onCompletion {
            log(VERBOSE) { "Collection<APath>.delete(options=$options): Deleted $targets" }
        }
}

suspend fun <T : APath<T>> T.file(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    readWrite: Boolean,
): FileHandle {
    return gateway.file(this, readWrite)
}

suspend fun <T : APath<T>> T.createSymlink(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    target: T
): Boolean {
    return gateway.createSymlink(this, target)
}

suspend fun <T : APath<T>> T.setModifiedAt(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    modifiedAt: Instant
): Boolean {
    return gateway.setModifiedAt(this, modifiedAt)
}

suspend fun <T : APath<T>> T.setPermissions(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    permissions: Permissions
): Boolean {
    return gateway.setPermissions(this, permissions)
}

suspend fun <T : APath<T>> T.setOwnership(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    ownership: Ownership
): Boolean {
    return gateway.setOwnership(this, ownership)
}

suspend fun <P : APath<P>, PL : APathLookup<P>, PLE : APathLookupExtended<P>> P.lookup(gateway: APathGateway<P, PL, PLE>): PL {
    return gateway.lookup(this)
}

suspend fun <P : APath<P>, PL : APathLookup<P>, PLE : APathLookupExtended<P>> P.lookupFiles(gateway: APathGateway<P, PL, PLE>): Collection<PL> {
    return gateway.lookupFiles(this)
}

suspend fun <P : APath<P>, PL : APathLookup<P>, PLE : APathLookupExtended<P>> P.lookupFilesOrNull(gateway: APathGateway<P, PL, PLE>): Collection<PL>? {
    return if (exists(gateway)) gateway.lookupFiles(this) else null
}

suspend fun <T : APath<T>> T.listFiles(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Collection<T> {
    return gateway.listFiles(this)
}

suspend fun <T : APath<T>> T.canRead(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.canRead(this)
}

suspend fun <T : APath<T>> T.canWrite(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.canWrite(this)
}

suspend fun <T : APath<T>> T.isFile(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.lookup(this).fileType == FileType.FILE
}

suspend fun <T : APath<T>> T.isDirectory(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): Boolean {
    return gateway.lookup(this).fileType == FileType.DIRECTORY
}

suspend fun <T : APath<T>> T.getFileSystemInfo(gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>): FileSystem {
    return gateway.getFileSystem(this)
}

suspend fun <P : APath<P>, PL : APathLookup<P>> P.copy(
    gateway: APathGateway<P, PL, out APathLookupExtended<P>>,
    destination: P,
    options: CopyAction.Options = CopyAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<CopyAction.State<P, PL, P, PL>> {
    return gateway.copy(sources = setOf(this), destination = destination, options = options, onIssue = onIssue)
        .onCompletion {
            log(VERBOSE) { "P.copy(destination=$destination, options=$options, onIssue=$onIssue): Copied $this" }
        }
}

suspend fun <P : APath<P>, PL : APathLookup<P>> Set<P>.copy(
    gateway: APathGateway<P, PL, out APathLookupExtended<P>>,
    destination: P,
    options: CopyAction.Options = CopyAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<CopyAction.State<P, PL, P, PL>> {
    return gateway.copy(sources = this, destination = destination, options = options, onIssue = onIssue)
        .onCompletion {
            log(VERBOSE) { "Set<P>.copy(destination=$destination, options=$options, onIssue=onIssue): Copied $this" }
        }
}

suspend fun <P : APath<P>, PL : APathLookup<P>> P.move(
    gateway: APathGateway<P, PL, out APathLookupExtended<P>>,
    destination: P,
    options: MoveAction.Options = MoveAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<MoveAction.State<P, PL, P, PL>> {
    return gateway.move(sources = setOf(this), destination = destination, options = options, onIssue = onIssue)
        .onCompletion {
            log(VERBOSE) { "T.move(destination=$destination, options=$options, onIssue=$onIssue): Moved $this" }
        }
}

suspend fun <P : APath<P>, PL : APathLookup<P>> Set<P>.move(
    gateway: APathGateway<P, PL, out APathLookupExtended<P>>,
    destination: P,
    options: MoveAction.Options = MoveAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<MoveAction.State<P, PL, P, PL>> {
    return gateway.move(sources = this, destination = destination, options = options, onIssue = onIssue)
        .onCompletion {
            log(VERBOSE) { "Set<T>.move(destination=$destination, options=$options, onIssue=$onIssue): Moved $this" }
        }
}
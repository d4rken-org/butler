package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LookupOptions
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

suspend fun <P : APath<P>, PL : APathLookup<P>, GT : APathGateway<P, PL>> P.walk(
    gateway: GT,
    lookupOptions: LookupOptions,
    walkOptions: APathGateway.WalkOptions<P, PL> = APathGateway.WalkOptions()
): Flow<PL> {
    return gateway.walk(this, lookupOptions, walkOptions)
}

suspend fun <P : APath<P>, PL : APathLookup<P>, GT : APathGateway<P, PL>> P.du(
    gateway: GT,
    options: APathGateway.DuOptions<P, PL> = APathGateway.DuOptions()
): Long {
    return gateway.du(this, options)
}

suspend fun <T : APath<T>> T.exists(gateway: APathGateway<T, out APathLookup<T>>): Boolean {
    return gateway.exists(this)
}

suspend fun <T : APath<T>> T.requireExists(gateway: APathGateway<T, out APathLookup<T>>): T {
    if (!exists(gateway)) {
        throw IllegalStateException("Path doesn't exist, but should: $this")
    }
    return this
}

suspend fun <T : APath<T>> T.requireNotExists(gateway: APathGateway<T, out APathLookup<T>>): T {
    if (exists(gateway)) {
        throw IllegalStateException("Path exist, but shouldn't: $this")
    }
    return this
}

suspend fun <T : APath<T>> T.createFileIfNecessary(gateway: APathGateway<T, out APathLookup<T>>): T {
    if (exists(gateway)) {
        if (gateway.lookup(this, LookupOptions()).fileType == FileType.FILE) {
            log(VERBOSE) { "File already exists, not creating: $this" }
            return this
        } else {
            throw IOException("Exists, but is not a file: $this")
        }
    }

    return createFile(gateway)
}

suspend fun <T : APath<T>> T.createFile(gateway: APathGateway<T, out APathLookup<T>>): T {
    gateway.createFile(this)
    log(VERBOSE) { "File created: $this" }
    return this
}

suspend fun <T : APath<T>> T.createDirIfNecessary(gateway: APathGateway<T, out APathLookup<T>>): T {
    if (exists(gateway)) {
        if (gateway.lookup(this, LookupOptions()).isDirectory) {
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
    gateway: APathGateway<P, PL>,
    options: DeleteAction.Options<P>,
) = setOf(this).delete(gateway, options)

suspend fun <P : APath<P>, PL : APathLookup<P>> Collection<P>.delete(
    gateway: APathGateway<P, PL>,
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
    gateway: APathGateway<T, out APathLookup<T>>,
    readWrite: Boolean,
): FileHandle = gateway.file(this, readWrite)

suspend fun <T : APath<T>> T.createSymlink(
    gateway: APathGateway<T, out APathLookup<T>>,
    target: T
): Boolean = gateway.createSymlink(this, target)

suspend fun <T : APath<T>> T.setModifiedAt(
    gateway: APathGateway<T, out APathLookup<T>>,
    modifiedAt: Instant
): Boolean = gateway.setModifiedAt(this, modifiedAt)

suspend fun <T : APath<T>> T.setPermissions(
    gateway: APathGateway<T, out APathLookup<T>>,
    permissions: Permissions
): Boolean = gateway.setPermissions(this, permissions)

suspend fun <T : APath<T>> T.setOwnership(
    gateway: APathGateway<T, out APathLookup<T>>,
    ownership: Ownership
): Boolean = gateway.setOwnership(this, ownership)

suspend fun <P : APath<P>, PL : APathLookup<P>> P.lookup(
    gateway: APathGateway<P, PL>,
    options: LookupOptions
): PL = gateway.lookup(this, options)

suspend fun <P : APath<P>, PL : APathLookup<P>> P.lookupFiles(
    gateway: APathGateway<P, PL>,
    options: LookupOptions
): Collection<PL> = gateway.lookupFiles(this, options)

suspend fun <T : APath<T>> T.listFiles(
    gateway: APathGateway<T, out APathLookup<T>>
): Collection<T> = gateway.listFiles(this)

suspend fun <T : APath<T>> T.canRead(
    gateway: APathGateway<T, out APathLookup<T>>
): Boolean = gateway.canRead(this)

suspend fun <T : APath<T>> T.canWrite(
    gateway: APathGateway<T, out APathLookup<T>>
): Boolean = gateway.canWrite(this)

suspend fun <T : APath<T>> T.isFile(
    gateway: APathGateway<T, out APathLookup<T>>
): Boolean = gateway.lookup(this, LookupOptions()).fileType == FileType.FILE

suspend fun <T : APath<T>> T.isDirectory(
    gateway: APathGateway<T, out APathLookup<T>>
): Boolean = gateway.lookup(this, LookupOptions()).fileType == FileType.DIRECTORY

suspend fun <T : APath<T>> T.getFileSystemInfo(
    gateway: APathGateway<T, out APathLookup<T>>
): FileSystem = gateway.getFileSystem(this)

suspend fun <P : APath<P>, PL : APathLookup<P>> P.copy(
    gateway: APathGateway<P, PL>,
    destination: P,
    options: CopyAction.Options = CopyAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<CopyAction.State<P, PL, P, PL>> = gateway.copy(
    sources = setOf(this),
    destination = destination,
    options = options,
    onIssue = onIssue
).onCompletion {
    log(VERBOSE) { "P.copy(destination=$destination, options=$options, onIssue=$onIssue): Copied $this" }
}

suspend fun <P : APath<P>, PL : APathLookup<P>> Set<P>.copy(
    gateway: APathGateway<P, PL>,
    destination: P,
    options: CopyAction.Options = CopyAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<CopyAction.State<P, PL, P, PL>> = gateway.copy(
    sources = this,
    destination = destination,
    options = options,
    onIssue = onIssue
).onCompletion {
    log(VERBOSE) { "Set<P>.copy(destination=$destination, options=$options, onIssue=onIssue): Copied $this" }
}

suspend fun <P : APath<P>, PL : APathLookup<P>> P.move(
    gateway: APathGateway<P, PL>,
    destination: P,
    options: MoveAction.Options = MoveAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<MoveAction.State<P, PL, P, PL>> =
    gateway.move(
        sources = setOf(this),
        destination = destination,
        options = options,
        onIssue = onIssue
    ).onCompletion {
        log(VERBOSE) { "T.move(destination=$destination, options=$options, onIssue=$onIssue): Moved $this" }
    }

suspend fun <P : APath<P>, PL : APathLookup<P>> Set<P>.move(
    gateway: APathGateway<P, PL>,
    destination: P,
    options: MoveAction.Options = MoveAction.Options(),
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
): Flow<MoveAction.State<P, PL, P, PL>> = gateway.move(
    sources = this,
    destination = destination,
    options = options,
    onIssue = onIssue
).onCompletion {
    log(VERBOSE) { "Set<T>.move(destination=$destination, options=$options, onIssue=$onIssue): Moved $this" }
}
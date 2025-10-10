package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import eu.darken.butler.common.files.saf.SAFPathLookup
import eu.darken.butler.common.files.saf.SAFPathLookupExtended

/**
 * Cross-type operations for copying and moving between different path types.
 *
 * These extensions enable operations like:
 * - SAFPath → LocalPath (export from cloud/SAF to local storage)
 * - LocalPath → SAFPath (import from local storage to cloud/SAF)
 * - FTPPath → LocalPath (download from FTP)
 * - LocalPath → FTPPath (upload to FTP)
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Copy from SAF storage to local file system
 * val safSources: Collection<SAFPath> = ...
 * val localDest: LocalPath = ...
 *
 * safSources.copyToLocal(
 *     destination = localDest,
 *     sourceOps = safGateway,   // Gateway implements FileSystemOps
 *     destOps = localGateway    // Gateway implements FileSystemOps
 * )
 *
 * // Or use standalone FileSystemOps (e.g., in root process)
 * safSources.copyToLocal(
 *     destination = localDest,
 *     sourceOps = SAFFileSystemOps(...),
 *     destOps = LocalFileSystemOps()
 * )
 * ```
 */

/**
 * Copy from SAFPath to LocalPath (cross-type).
 */
suspend fun Collection<SAFPath>.copyToLocal(
    destination: LocalPath,
    sourceOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
    destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
    onProgress: (suspend (CopyAction.State.Progress<SAFPath, SAFPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<SAFPath, SAFPathLookup> {
    return this.copyGeneric(
        destination = destination,
        sourceOps = sourceOps,
        destOps = destOps,
        strategy = GenericCrossTypeCopyStrategy(),
        onProgress = onProgress,
        onIssue = onIssue
    )
}

/**
 * Copy from LocalPath to SAFPath (cross-type).
 */
suspend fun Collection<LocalPath>.copyToSAF(
    destination: SAFPath,
    sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
    destOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<LocalPath, LocalPathLookup> {
    return this.copyGeneric(
        destination = destination,
        sourceOps = sourceOps,
        destOps = destOps,
        strategy = GenericCrossTypeCopyStrategy(),
        onProgress = onProgress,
        onIssue = onIssue
    )
}

/**
 * Move from SAFPath to LocalPath (cross-type).
 */
suspend fun Collection<SAFPath>.moveToLocal(
    destination: LocalPath,
    sourceOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
    destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
    onProgress: (suspend (MoveAction.State.Progress<SAFPath, SAFPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): MoveAction.State.Result<SAFPath, SAFPathLookup> {
    return this.moveGeneric(
        destination = destination,
        sourceOps = sourceOps,
        destOps = destOps,
        strategy = GenericCrossTypeMoveStrategy(),
        onProgress = onProgress,
        onIssue = onIssue
    )
}

/**
 * Move from LocalPath to SAFPath (cross-type).
 */
suspend fun Collection<LocalPath>.moveToSAF(
    destination: SAFPath,
    sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
    destOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
    onProgress: (suspend (MoveAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): MoveAction.State.Result<LocalPath, LocalPathLookup> {
    return this.moveGeneric(
        destination = destination,
        sourceOps = sourceOps,
        destOps = destOps,
        strategy = GenericCrossTypeMoveStrategy(),
        onProgress = onProgress,
        onIssue = onIssue
    )
}

// Single-path convenience extensions

suspend fun SAFPath.copyToLocal(
    destination: LocalPath,
    sourceOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
    destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
    onProgress: (suspend (CopyAction.State.Progress<SAFPath, SAFPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<SAFPath, SAFPathLookup> =
    setOf(this).copyToLocal(destination, sourceOps, destOps, onProgress, onIssue)

suspend fun LocalPath.copyToSAF(
    destination: SAFPath,
    sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
    destOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
    onProgress: (suspend (CopyAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): CopyAction.State.Result<LocalPath, LocalPathLookup> =
    setOf(this).copyToSAF(destination, sourceOps, destOps, onProgress, onIssue)

suspend fun SAFPath.moveToLocal(
    destination: LocalPath,
    sourceOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
    destOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
    onProgress: (suspend (MoveAction.State.Progress<SAFPath, SAFPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): MoveAction.State.Result<SAFPath, SAFPathLookup> =
    setOf(this).moveToLocal(destination, sourceOps, destOps, onProgress, onIssue)

suspend fun LocalPath.moveToSAF(
    destination: SAFPath,
    sourceOps: FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>,
    destOps: FileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>,
    onProgress: (suspend (MoveAction.State.Progress<LocalPath, LocalPathLookup>) -> Unit)? = null,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): MoveAction.State.Result<LocalPath, LocalPathLookup> =
    setOf(this).moveToSAF(destination, sourceOps, destOps, onProgress, onIssue)

package eu.darken.butler.common.files.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.permissions.PermissionErrorClassifier
import eu.darken.butler.common.progress.Progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.isActive

/**
 * Generic create operation that works with any path type.
 *
 * Create is simpler than copy/move/delete as it:
 * 1. Operates on a single target path (not a collection)
 * 2. Doesn't need scanning or recursive tree traversal
 * 3. Is typically an instant operation (no progress tracking needed)
 * 4. Checks for conflicts upfront via lookup (not during execution)
 *
 * ## Algorithm
 *
 * 1. **Conflict Detection**: Check if target exists via lookup
 * 2. **Conflict Resolution**: Handle rename/overwrite/cancel via user callback
 * 3. **Creation**: Create file or directory via FileSystemOps
 * 4. **Error Handling**: Wrap creation errors and support retry
 * 5. **Result**: Return lookup of created path
 *
 * ## Conflict Handling
 *
 * - **Rename**: Update target path and recheck for conflicts (loop)
 * - **Overwrite**: Delete existing path recursively, then proceed to creation
 * - **Cancel**: Throw CancellationException
 *
 * @param P The path type (LocalPath, SAFPath, etc.)
 * @param PL The path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 */
internal class GenericPathCreate<P : APath<P>, PL : APathLookup<P>>(
    private val target: P,
    private val type: CreateAction.CreateType,
    private val fileSystemOps: FileSystemOps<P, PL>,
    private val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
) {
    private val tag = logTag("FileOps", "Generic", "Create")
    private val issueResolver = PathOperationIssueResolver(onIssue)
    private val namingUtils = GenericPathNamingUtils(ops = fileSystemOps)

    // Use channelFlow to support emissions after IPC callbacks (which use runBlocking on client side)
    fun execute(): Flow<CreateAction.State<P, PL>> = channelFlow {
        log(tag, DEBUG) { "execute(): target=$target, type=$type" }

        var currentTarget = target

        // Loop to handle conflicts with rename resolution
        while (currentCoroutineContext().isActive) {
            // Check if target already exists
            val existingLookup = fileSystemOps.lookup(
                currentTarget,
                LookupOptions(fallbackToUnknown = true)
            )

            if (existingLookup.fileType != FileType.UNKNOWN) {
                log(tag, DEBUG) { "Target already exists: $existingLookup" }

                // The sheet's apply-to-all rename resolves with suggestedName directly, so
                // offering rename without one leaves it nothing to apply.
                val issue = PathActionIssue.PathAlreadyExists(
                    destination = existingLookup,
                    canRenameSource = true,
                    canOverwrite = true,
                    canSkip = false,
                    suggestedName = currentTarget.parent
                        ?.let { namingUtils.generateUniqueName(it, currentTarget.name, knownToExist = true) }
                        ?: "${currentTarget.name} (1)",
                )

                when (val resolution = issueResolver.resolveIssue(issue)) {
                    is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                        log(tag, INFO) { "Renaming to: ${resolution.newName}" }
                        // Update target with new name
                        currentTarget = currentTarget.parent?.child(resolution.newName)
                            ?: throw IllegalStateException("Cannot rename root path")
                        // Continue loop to check new name
                        continue
                    }

                    is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                        log(tag, INFO) { "Overwriting existing path" }
                        // Delete existing path first
                        currentTarget.deleteGeneric(
                            fileSystemOps = fileSystemOps,
                            recursive = true,
                            ignoreMissing = false,
                            onIssue = onIssue
                        ).last()
                        // Exit conflict loop, proceed to creation
                        break
                    }

                    is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> {
                        throw CancellationException("Create operation cancelled by user")
                    }

                    is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                        throw IllegalArgumentException("RenameDestination not supported for create")
                    }

                    is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                        throw IllegalArgumentException("Merge not supported for create")
                    }

                    is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                        throw IllegalStateException("Skip not supported for create (canSkip=false)")
                    }
                }
            } else {
                // Path doesn't exist, proceed to creation
                break
            }
        }

        // Send active state
        send(
            CreateAction.State.Active(
                target = currentTarget,
                type = type,
                primaryProgress = Progress.Data(
                    primary = "Creating…".toCaString(),
                    count = Progress.Count.Indeterminate(),
                )
            )
        )

        // Create file or directory with retry logic
        while (currentCoroutineContext().isActive) {
            try {
                when (type) {
                    CreateAction.CreateType.FILE -> {
                        log(tag, DEBUG) { "Creating file: $currentTarget" }
                        fileSystemOps.createFile(currentTarget)
                    }

                    CreateAction.CreateType.DIRECTORY -> {
                        log(tag, DEBUG) { "Creating directory: $currentTarget" }
                        fileSystemOps.createDir(currentTarget)
                    }
                }
                break // Creation succeeded, exit retry loop
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(tag, ERROR) { "Create failed: $currentTarget - $e" }

                // If no issue handler, re-throw
                if (onIssue == null) throw e

                val issue: PathActionIssue = if (PermissionErrorClassifier.isPermissionError(e)) {
                    PathActionIssue.InsufficientPermission(
                        destinationPath = currentTarget,
                        canSkip = false,
                        exception = e,
                    )
                } else {
                    PathActionIssue.UnknownError(
                        exception = e,
                        errorMessage = (e.message ?: e.toString()).toCaString(),
                        destinationPath = currentTarget,
                        canRetry = true,
                        canSkip = false,
                    )
                }

                when (val resolution = issueResolver.resolveIssue(issue)) {
                    is PathActionIssue.UnknownError.Resolution.Retry -> {
                        log(tag, INFO) { "Retrying creation: $currentTarget" }
                        continue // Retry creation
                    }

                    is PathActionIssue.UnknownError.Resolution.Cancel -> {
                        throw CancellationException("Create operation cancelled by user")
                    }

                    is PathActionIssue.UnknownError.Resolution.Skip -> {
                        throw IllegalStateException("Skip not supported for create (canSkip=false)")
                    }

                    is PathActionIssue.InsufficientPermission.Resolution.Cancel -> {
                        throw CancellationException("Create operation cancelled by user")
                    }

                    is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                        throw IllegalStateException("Skip not supported for create (canSkip=false)")
                    }
                }
            }
        }

        // Lookup the created path
        val created = fileSystemOps.lookup(currentTarget, LookupOptions.BASE)
        log(tag, INFO) { "Created: $created" }

        send(CreateAction.State.Completed(created))
    }
}

/**
 * Extension function for creating a file or directory.
 */
fun <P : APath<P>, PL : APathLookup<P>> P.createGeneric(
    fileSystemOps: FileSystemOps<P, PL>,
    type: CreateAction.CreateType,
    onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null
): Flow<CreateAction.State<P, PL>> = GenericPathCreate(
    target = this,
    type = type,
    fileSystemOps = fileSystemOps,
    onIssue = onIssue
).execute()

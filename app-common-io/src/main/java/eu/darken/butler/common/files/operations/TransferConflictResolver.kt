package eu.darken.butler.common.files.operations

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.metadata.FileType

/**
 * Shared conflict resolution logic for path transfer operations (copy/move).
 *
 * Handles the complex logic of resolving conflicts when destination paths already exist,
 * including "apply to all" support, rename/overwrite/merge/skip options, and work queue
 * management for retries.
 *
 * ## Conflict Types
 *
 * - **File conflicts**: Destination file already exists
 * - **Directory conflicts**: Destination directory already exists
 *   - Can merge (for directories)
 *   - Can overwrite (replaces existing)
 *   - Can skip (leaves existing)
 *   - Can rename source or destination
 *
 * ## Usage Pattern
 *
 * ```kotlin
 * val resolver = TransferConflictResolver<SP, SPL, DP, DPL>(
 *     destOps = destOps,
 *     issueResolver = issueResolver,
 *     progressTracker = progressTracker,
 *     tag = TAG
 * )
 *
 * resolver.handleFileConflict(
 *     sourceLookup = fileLookup,
 *     destination = destPath,
 *     destLookup = existingFileLookup,
 *     onSkip = { skipped.add(it) },
 *     onRename = { newDest -> workQueue.addFirst(createRenamedItem(newDest)) },
 *     onOverwrite = { destOps.delete(destPath); workQueue.addFirst(originalItem) },
 *     onResolveConflict = { ... }
 * )
 * ```
 *
 * @param SP Source path type
 * @param SPL Source path lookup type
 * @param DP Destination path type
 * @param DPL Destination path lookup type
 */
class TransferConflictResolver<
    SP : APath<SP>, SPL : APathLookup<SP>,
    DP : APath<DP>, DPL : APathLookup<DP>
>(
    private val destOps: FileSystemOps<DP, DPL>,
    private val issueResolver: PathOperationIssueResolver,
    private val progressTracker: PathOperationProgressTracker,
    private val tag: String
) {

    private val namingUtils = GenericPathNamingUtils(ops = destOps)

    /**
     * Result of checking "apply to all" flags for conflict resolution.
     */
    private sealed class ApplyToAllResult {
        /** Conflict was auto-resolved, no further action needed */
        data object Resolved : ApplyToAllResult()
        /** No "apply to all" flag matched, need to resolve conflict with user */
        data object NeedUserInput : ApplyToAllResult()
    }

    /**
     * Checks "apply to all" flags and executes corresponding actions.
     * Extracts common logic between file and directory conflict handlers.
     *
     * @param sourceLookup Source lookup
     * @param destination Destination path
     * @param destLookup Destination lookup (for checking type)
     * @param onSkip Called when skip-all is active
     * @param onRename Called when rename-all is active (receives new destination)
     * @param onMerge Called when merge-all is active (directories only, null for files)
     * @param onOverwrite Called when overwrite-all is active (receives whether recursive)
     * @return ApplyToAllResult indicating whether conflict was resolved or needs user input
     */
    private suspend fun checkApplyToAllFlags(
        sourceLookup: SPL,
        destination: DP,
        destLookup: DPL,
        onSkip: (SPL) -> Unit,
        onRename: (DP) -> Unit,
        onMerge: (() -> Unit)? = null,
        onOverwrite: (recursive: Boolean) -> Unit
    ): ApplyToAllResult {
        // Skip all
        if (issueResolver.skipAllPathExists) {
            log(tag, INFO) { "Skipping (apply-to-all): $destination" }
            onSkip(sourceLookup)
            progressTracker.completeItem()
            return ApplyToAllResult.Resolved
        }

        // Rename source all
        if (issueResolver.renameSourceAllPathExists) {
            val uniqueName = generateUniqueName(destination)
            val parentPath = destination.parent!!
            val renamedDest = parentPath.child(uniqueName)
            log(tag, INFO) { "Auto-renaming (apply-to-all): $destination -> $renamedDest" }
            onRename(renamedDest)
            return ApplyToAllResult.Resolved
        }

        // Merge all (directories only)
        if (onMerge != null && destLookup.fileType == FileType.DIRECTORY && issueResolver.mergeAllPathExists) {
            log(tag, INFO) { "Merging directory (apply-to-all): $destination" }
            onMerge()
            return ApplyToAllResult.Resolved
        }

        // Overwrite all
        if (issueResolver.overwriteAllPathExists) {
            val recursive = destLookup.fileType == FileType.DIRECTORY
            log(tag, INFO) { "Overwriting (apply-to-all): $destination" }
            destOps.delete(destination, recursive = recursive)
            onOverwrite(recursive)
            return ApplyToAllResult.Resolved
        }

        return ApplyToAllResult.NeedUserInput
    }

    /**
     * Handles file conflict (destination file already exists).
     *
     * Checks "apply to all" flags and either auto-resolves or prompts user for decision.
     *
     * @param sourceLookup Source file lookup
     * @param destination Destination path (where conflict occurred)
     * @param destLookup Existing destination file lookup
     * @param onSkip Callback when file should be skipped
     * @param onRename Callback when file should be renamed (receives new destination path)
     * @param onOverwrite Callback when file should be overwritten
     * @param onResolveConflict Callback to queue conflict resolution work item
     * @param onIssue User callback for resolving issues (null if no handler)
     */
    suspend fun handleFileConflict(
        sourceLookup: SPL,
        destination: DP,
        destLookup: DPL,
        onSkip: (SPL) -> Unit,
        onRename: (DP) -> Unit,
        onOverwrite: () -> Unit,
        onResolveConflict: () -> Unit,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ) {
        // Check "apply to all" flags using common logic
        val applyToAllResult = checkApplyToAllFlags(
            sourceLookup = sourceLookup,
            destination = destination,
            destLookup = destLookup,
            onSkip = onSkip,
            onRename = onRename,
            onMerge = null, // Files don't support merge
            onOverwrite = { _ -> onOverwrite() }
        )

        if (applyToAllResult == ApplyToAllResult.Resolved) return

        // Check if we have an issue handler
        if (onIssue == null) {
            throw eu.darken.butler.common.files.errors.WriteException(
                path = destination,
                message = "File already exists: $destination"
            )
        }

        // Queue conflict resolution
        onResolveConflict()
    }

    /**
     * Handles directory conflict (destination directory already exists).
     *
     * Checks "apply to all" flags and either auto-resolves or prompts user for decision.
     * Directories have additional "merge" option not available for files.
     *
     * @param sourceLookup Source directory lookup
     * @param destination Destination path (where conflict occurred)
     * @param destLookup Existing destination directory lookup
     * @param onSkip Callback when directory should be skipped (receives source path and whether to mark as skipped dir)
     * @param onRename Callback when directory should be renamed (receives new destination path)
     * @param onMerge Callback when directory should be merged
     * @param onOverwrite Callback when directory should be overwritten
     * @param onResolveConflict Callback to queue conflict resolution work item
     * @param onIssue User callback for resolving issues (null if no handler)
     */
    suspend fun handleDirectoryConflict(
        sourceLookup: SPL,
        destination: DP,
        destLookup: DPL,
        onSkip: (SPL, markAsSkippedDir: Boolean) -> Unit,
        onRename: (DP) -> Unit,
        onMerge: () -> Unit,
        onOverwrite: (recursive: Boolean) -> Unit,
        onResolveConflict: () -> Unit,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ) {
        // Check "apply to all" flags using common logic
        val applyToAllResult = checkApplyToAllFlags(
            sourceLookup = sourceLookup,
            destination = destination,
            destLookup = destLookup,
            onSkip = { lookup -> onSkip(lookup, true) }, // Mark as skipped directory
            onRename = onRename,
            onMerge = if (destLookup.fileType == FileType.DIRECTORY) onMerge else null,
            onOverwrite = onOverwrite
        )

        if (applyToAllResult == ApplyToAllResult.Resolved) return

        // Auto-merge directories when no issue handler (backward compatibility)
        if (destLookup.fileType == FileType.DIRECTORY && onIssue == null) {
            log(tag, VERBOSE) { "Directory already exists, auto-merging: $destination" }
            onMerge()
            return
        }

        // Queue conflict resolution
        onResolveConflict()
    }

    /**
     * Processes conflict resolution after user input.
     *
     * Creates appropriate PathActionIssue, invokes user callback, and executes
     * the resolution (skip, overwrite, merge, rename source, rename destination).
     *
     * @param sourceLookup Source lookup
     * @param destination Destination path
     * @param destLookup Existing destination lookup
     * @param canMerge Whether merge is an option (true for directory-to-directory)
     * @param onSkip Callback when user chooses to skip (receives source path and whether to mark as skipped dir)
     * @param onOverwrite Callback when user chooses to overwrite (receives whether recursive delete needed)
     * @param onMerge Callback when user chooses to merge
     * @param onRenameSource Callback when user renames source (receives new destination path)
     * @param onRenameDestination Callback when user renames destination (receives original item to retry)
     */
    suspend fun processResolveConflict(
        sourceLookup: SPL,
        destination: DP,
        destLookup: DPL,
        canMerge: Boolean,
        onSkip: (SPL, markAsSkippedDir: Boolean) -> Unit,
        onOverwrite: (recursive: Boolean) -> Unit,
        onMerge: () -> Unit,
        onRenameSource: (DP) -> Unit,
        onRenameDestination: () -> Unit
    ) {
        val suggestedName = generateUniqueName(destination)

        val issue = PathActionIssue.PathAlreadyExists(
            source = sourceLookup,
            destination = destLookup,
            canSkip = true,
            canOverwrite = true,
            canMerge = canMerge,
            canRenameSource = true,
            canRenameDestination = true,
            suggestedName = suggestedName,
        )

        when (val resolution = issueResolver.resolveIssue(issue) as PathActionIssue.PathAlreadyExists.Resolution) {
            is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                val isDirectory = canMerge // canMerge is true only for directory conflicts
                onSkip(sourceLookup, isDirectory)
                progressTracker.completeItem()
            }

            is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                val recursive = destLookup.fileType == FileType.DIRECTORY
                destOps.delete(destination, recursive = recursive)
                onOverwrite(recursive)
            }

            is PathActionIssue.PathAlreadyExists.Resolution.Merge -> {
                onMerge()
                progressTracker.completeItem()
            }

            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                val parentPath = destination.parent!!
                val renamedDest = parentPath.child(resolution.newName)
                log(tag, INFO) { "Renaming destination: $destination -> $renamedDest" }
                onRenameSource(renamedDest)
            }

            is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                val parentPath = destination.parent!!
                val newDestPath = parentPath.child(resolution.newName)
                log(tag, INFO) { "Renaming existing destination: $destination -> $newDestPath" }
                // Move the existing destination to the new name
                destOps.move(destination, newDestPath)
                // Re-queue original operation (destination path now clear)
                onRenameDestination()
            }

            is PathActionIssue.PathAlreadyExists.Resolution.Cancel -> {
                throw kotlin.coroutines.cancellation.CancellationException("User cancelled")
            }
        }
    }

    /**
     * Generates a unique filename by checking destination file system.
     *
     * @param path The path to make unique
     * @return A unique filename (e.g., "file (1).txt", "folder (2)")
     */
    private suspend fun generateUniqueName(path: DP): String =
        path.parent?.let { parentPath ->
            namingUtils.generateUniqueName(
                parentPath = parentPath,
                originalName = path.name,
                knownToExist = true  // We're in conflict resolution, so we know it exists
            )
        } ?: "${path.name} (1)"
}

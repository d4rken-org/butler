package eu.darken.butler.saver.core.operations

import android.content.Context
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Save
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.operations.core.PathOperationIssueResolver
import eu.darken.butler.common.files.local.operations.core.PathOperationProgressTracker
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.StagedReplace
import eu.darken.butler.common.files.permissions.PermissionErrorClassifier
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import eu.darken.butler.workspace.core.operations.buildTransferProgressMetrics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

class SaveFilesOperation @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val command: Command,
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val issueHandler: IssueHandler,
) : Operation {

    private val tag = logTag("Saver", "Operation", "SaveFiles", workspaceId.shortTag)

    data class Command(
        val sources: List<SourceFile>,
        val targetDirectory: APath<*>,
    ) {
        data class SourceFile(
            val uri: Uri,
            val filename: String,
            val size: Long?,
        )
    }

    private val plannedFiles = command.sources.map { command.targetDirectory.child(it.filename) }

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin: Operation.Metadata.Origin = Operation.Metadata.Origin.Saver(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Save
        override val title: CaString = R.string.saver_operation_title.toCaString()
        override val description: CaString = caString { cx ->
            cx.getQuantityString2(
                R.plurals.saver_operation_description,
                command.sources.size,
                command.sources.size,
                command.targetDirectory.userReadablePath.get(cx),
            )
        }
        override val kind = Operation.Metadata.Kind.SAVE
        override val pathPlan = OperationPathPlan(
            targets = plannedFiles,
            destination = OperationPathPlan.Destination.Container(command.targetDirectory),
            // The target directory is already the parent of every planned file; promoting it to a
            // candidate of its own would add ITS parent to the attempted-paths rows.
            scopePaths = plannedFiles,
        )
    }

    override fun perform(operationContext: Operation.Context): Flow<Operation.State> = channelFlow {
        log(tag, INFO) { "perform(): Starting save of ${command.sources.size} files to ${command.targetDirectory}" }

        val progressTracker = PathOperationProgressTracker()
        progressTracker.totalItems = command.sources.size
        progressTracker.totalBytes = command.sources.sumOf { it.size ?: 0L }

        val issueResolver = PathOperationIssueResolver { issue ->
            send(
                State.Waiting(
                    startedAt = operationContext.startedAt,
                    waitingSince = Clock.System.now(),
                    issue = issue,
                )
            )
            issueHandler.handleIssue(operationContext.id, issue) as PathActionIssue.Resolution
        }

        var stateActive = State.Active(startedAt = operationContext.startedAt)
        send(stateActive)

        val results = mutableListOf<SaveFilesReport.FileResult>()

        command.sources.forEachIndexed { index, source ->
            currentCoroutineContext().ensureActive()

            log(tag, DEBUG) { "Saving file ${index + 1}/${command.sources.size}: ${source.filename}" }

            progressTracker.startFile(source.size ?: 0L)

            val result = saveFile(
                source = source,
                targetDirectory = command.targetDirectory,
                operationContext = operationContext,
                progressTracker = progressTracker,
                issueResolver = issueResolver,
                emitState = { newState ->
                    stateActive = newState
                    send(newState)
                },
            )

            results.add(result)
            progressTracker.completeFile()
            progressTracker.completeItem()

            when (result) {
                is SaveFilesReport.FileResult.Success -> {
                    log(tag, INFO) { "Successfully saved: ${source.filename} -> ${result.savedPath}" }
                }
                is SaveFilesReport.FileResult.Skipped -> {
                    log(tag, INFO) { "Skipped: ${source.filename} - ${result.reason}" }
                }
                is SaveFilesReport.FileResult.Error -> {
                    log(tag, ERROR) { "Failed to save: ${source.filename} - ${result.error}" }
                }
            }

            // Emit final progress for this file
            if (progressTracker.shouldReportProgress(force = true)) {
                stateActive = buildActiveState(operationContext, source, progressTracker)
                send(stateActive)
            }
        }

        // Force final sample
        progressTracker.shouldReportProgress(force = true)

        val report = SaveFilesReport(
            results = results,
            performanceHistory = progressTracker.performanceHistory,
        )
        log(
            tag,
            INFO
        ) { "Save completed: ${report.successes.size} succeeded, ${report.skipped.size} skipped, ${report.errors.size} failed" }

        send(
            State.Completed(
                startedAt = operationContext.startedAt,
                report = report,
            )
        )
    }

    private suspend fun saveFile(
        source: Command.SourceFile,
        targetDirectory: APath<*>,
        operationContext: Operation.Context,
        progressTracker: PathOperationProgressTracker,
        issueResolver: PathOperationIssueResolver,
        emitState: suspend (State.Active) -> Unit,
    ): SaveFilesReport.FileResult {
        return try {
            val inputStream = context.contentResolver.openInputStream(source.uri)
                ?: return SaveFilesReport.FileResult.Error(
                    filename = source.filename,
                    error = IllegalStateException("Failed to open input stream for ${source.uri}"),
                )

            var targetPath = targetDirectory.child(source.filename)
            var replacesExisting = false

            // Check for conflicts
            if (gatewaySwitch.exists(targetPath)) {
                when (
                    val outcome = handleConflict(
                        source = source,
                        targetPath = targetPath,
                        targetDirectory = targetDirectory,
                        issueResolver = issueResolver,
                    )
                ) {
                    is ConflictOutcome.Skip -> {
                        inputStream.close()
                        return SaveFilesReport.FileResult.Skipped(
                            filename = source.filename,
                            reason = SaveFilesReport.FileResult.Skipped.SkipReason.CONFLICT,
                        )
                    }

                    is ConflictOutcome.Write -> {
                        targetPath = outcome.target
                        replacesExisting = outcome.replacesExisting
                    }
                }
            }

            // A replacement is written to a staging sibling and swapped in afterwards, so a failure
            // mid-write leaves the user with the old file instead of a truncated new one
            val writePath = if (replacesExisting) {
                StagedReplace.stagingPathFor(targetPath, gatewaySwitch)
            } else {
                targetPath
            }

            var bytesWritten = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            try {
                // Create file and write with progress tracking
                gatewaySwitch.createFile(writePath, createParents = false)

                gatewaySwitch.openOutputStream(writePath, append = false).use { outputStream ->
                    inputStream.use { input ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead

                            progressTracker.updateFileProgress(bytesRead.toLong())

                            if (progressTracker.shouldReportProgress()) {
                                emitState(buildActiveState(operationContext, source, progressTracker))
                            }
                        }
                        outputStream.flush()
                    }
                }
            } catch (e: Throwable) {
                if (replacesExisting) StagedReplace.discard(writePath, gatewaySwitch)
                throw e
            }

            if (replacesExisting) StagedReplace.commit(writePath, targetPath, gatewaySwitch)

            SaveFilesReport.FileResult.Success(
                filename = source.filename,
                savedPath = targetPath,
                bytes = bytesWritten,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleError(e, source, issueResolver)
        }
    }

    /** What to do with a source file whose target already exists. */
    private sealed interface ConflictOutcome {
        data object Skip : ConflictOutcome

        /**
         * @param target Where to write
         * @param replacesExisting Whether content at [target] has to be replaced
         */
        data class Write(val target: APath<*>, val replacesExisting: Boolean) : ConflictOutcome
    }

    private suspend fun handleConflict(
        source: Command.SourceFile,
        targetPath: APath<*>,
        targetDirectory: APath<*>,
        issueResolver: PathOperationIssueResolver,
    ): ConflictOutcome {
        // Check "apply to all" flags first
        when {
            issueResolver.skipAllPathExists -> {
                log(tag, INFO) { "Skipping conflict (apply-to-all): ${source.filename}" }
                return ConflictOutcome.Skip
            }
            issueResolver.overwriteAllPathExists -> {
                log(tag, INFO) { "Overwriting (apply-to-all): ${source.filename}" }
                val existingType = gatewaySwitch.lookup(targetPath, LookupOptions.BASE).fileType
                return overwriteOutcome(targetPath, existingType)
            }
            issueResolver.renameSourceAllPathExists -> {
                val uniqueName = generateUniqueName(targetDirectory, source.filename)
                log(tag, INFO) { "Auto-renaming (apply-to-all): ${source.filename} -> $uniqueName" }
                return ConflictOutcome.Write(targetDirectory.child(uniqueName), replacesExisting = false)
            }
        }

        // Get lookup for conflict UI
        val destLookup = gatewaySwitch.lookup(targetPath, LookupOptions.BASE)
        val suggestedName = generateUniqueName(targetDirectory, source.filename)

        val issue = PathActionIssue.PathAlreadyExists(
            source = null,
            destination = destLookup,
            canSkip = true,
            canOverwrite = true,
            canMerge = false,
            canRenameSource = true,
            canRenameDestination = true,
            suggestedName = suggestedName,
        )

        return when (val resolution = issueResolver.resolveIssue(issue)) {
            is PathActionIssue.PathAlreadyExists.Resolution.Skip -> {
                log(tag, INFO) { "User chose to skip: ${source.filename}" }
                ConflictOutcome.Skip
            }

            is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                log(tag, INFO) { "User chose to overwrite: ${source.filename}" }
                overwriteOutcome(targetPath, destLookup.fileType)
            }

            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                log(tag, INFO) { "User chose to rename: ${source.filename} -> ${resolution.newName}" }
                ConflictOutcome.Write(targetDirectory.child(resolution.newName), replacesExisting = false)
            }

            is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                log(tag, INFO) { "User chose to rename existing: ${targetPath.name} -> ${resolution.newName}" }
                val newDestPath = targetDirectory.child(resolution.newName)
                if (gatewaySwitch.move(targetPath, newDestPath) !is MoveOutcome.Moved) {
                    // Writing to targetPath without a successful rename would collide with the existing file
                    throw WriteException("Could not rename existing file to ${resolution.newName}", targetPath)
                }
                ConflictOutcome.Write(targetPath, replacesExisting = false)
            }

            else -> ConflictOutcome.Write(targetPath, replacesExisting = false)
        }
    }

    /**
     * A file cannot be staged over a directory: the swap-in renames the directory aside and then
     * cannot remove a non-empty one, leaving the user's folder behind a dot-name while the save
     * reports success. A directory is refused here rather than by a delete that is allowed to
     * succeed - `DocumentsContract.deleteDocument` takes a whole subtree with it.
     */
    private suspend fun overwriteOutcome(targetPath: APath<*>, existingType: FileType): ConflictOutcome {
        if (existingType == FileType.DIRECTORY) {
            throw WriteException("Cannot overwrite the folder ${targetPath.name} with a file", targetPath)
        }
        return ConflictOutcome.Write(targetPath, replacesExisting = true)
    }

    private suspend fun handleError(
        error: Exception,
        source: Command.SourceFile,
        issueResolver: PathOperationIssueResolver,
    ): SaveFilesReport.FileResult {
        log(tag, ERROR) { "Error saving ${source.filename}: ${error.asLog()}" }

        val isPermissionError = error.isPermissionError()

        // Check "apply to all" flags
        when {
            isPermissionError && issueResolver.skipAllPermission -> {
                log(tag, INFO) { "Skipping permission error (apply-to-all): ${source.filename}" }
                return SaveFilesReport.FileResult.Skipped(
                    filename = source.filename,
                    reason = SaveFilesReport.FileResult.Skipped.SkipReason.PERMISSION_DENIED,
                )
            }
            !isPermissionError && issueResolver.skipAllUnknown -> {
                log(tag, INFO) { "Skipping unknown error (apply-to-all): ${source.filename}" }
                return SaveFilesReport.FileResult.Skipped(
                    filename = source.filename,
                    reason = SaveFilesReport.FileResult.Skipped.SkipReason.USER_SKIPPED,
                )
            }
        }

        // Create appropriate issue
        val issue = if (isPermissionError) {
            PathActionIssue.InsufficientPermission(
                source = null,
                destinationPath = command.targetDirectory,
                exception = error,
                canSkip = true,
            )
        } else {
            PathActionIssue.UnknownError(
                source = null,
                destinationPath = null,
                exception = error,
                canSkip = true,
                canRetry = false,
            )
        }

        return try {
            when (issueResolver.resolveIssue(issue)) {
                is PathActionIssue.InsufficientPermission.Resolution.Skip -> {
                    log(tag, INFO) { "User chose to skip permission error: ${source.filename}" }
                    SaveFilesReport.FileResult.Skipped(
                        filename = source.filename,
                        reason = SaveFilesReport.FileResult.Skipped.SkipReason.PERMISSION_DENIED,
                    )
                }

                is PathActionIssue.UnknownError.Resolution.Skip -> {
                    log(tag, INFO) { "User chose to skip error: ${source.filename}" }
                    SaveFilesReport.FileResult.Skipped(
                        filename = source.filename,
                        reason = SaveFilesReport.FileResult.Skipped.SkipReason.USER_SKIPPED,
                    )
                }

                else -> SaveFilesReport.FileResult.Error(
                    filename = source.filename,
                    error = error,
                )
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun Exception.isPermissionError(): Boolean =
        PermissionErrorClassifier.isPermissionError(this)

    private suspend fun generateUniqueName(directory: APath<*>, filename: String): String {
        val baseName = filename.substringBeforeLast('.', filename)
        val extension = if ('.' in filename) ".${filename.substringAfterLast('.')}" else ""

        var counter = 1
        var candidate = "$baseName ($counter)$extension"

        while (gatewaySwitch.exists(directory.child(candidate)) && counter < 1000) {
            counter++
            candidate = "$baseName ($counter)$extension"
        }

        if (counter >= 1000) {
            throw IllegalStateException("Could not find unique filename for $filename")
        }

        return candidate
    }

    private fun buildActiveState(
        operationContext: Operation.Context,
        currentSource: Command.SourceFile,
        progressTracker: PathOperationProgressTracker,
    ): State.Active {
        val snapshot = progressTracker.createSnapshot()
        val perfHistory = progressTracker.performanceHistory

        val metrics = buildTransferProgressMetrics(
            performanceHistory = perfHistory,
            totalBytes = snapshot.totalBytes,
            processedBytes = snapshot.processedBytes,
            currentFileSize = snapshot.currentFileSize,
            currentFileBytes = snapshot.currentFileBytes,
            currentFileStartTime = snapshot.currentFileStartTime,
            truncateItemSpeed = false,
            requireTotalBytesForEta = false,
        )

        val primaryProgress = Progress.Data(
            primary = caString { "${snapshot.itemsProcessed + 1} / ${snapshot.totalItems}" },
            secondary = metrics.overall ?: CaString.EMPTY,
            count = Progress.Count.Counter(
                current = snapshot.itemsProcessed + 1,
                max = snapshot.totalItems,
            ),
            extra = perfHistory,
        )

        val secondaryProgress = Progress.Data(
            primary = currentSource.filename.toCaString(),
            secondary = metrics.currentFile ?: CaString.EMPTY,
            count = Progress.Count.Size(
                current = snapshot.currentFileBytes,
                max = snapshot.currentFileSize,
            ),
        )

        return State.Active(
            startedAt = operationContext.startedAt,
            primaryProgress = primaryProgress,
            secondaryProgress = secondaryProgress,
            performanceHistory = perfHistory,
        )
    }

    sealed interface State : Operation.State {
        data class Active(
            override val startedAt: Instant,
            override val primaryProgress: Progress.Data = Progress.Data(),
            override val secondaryProgress: Progress.Data? = null,
            override val performanceHistory: PerformanceHistory? = null,
        ) : State, Operation.State.Active, Operation.HasPerformanceHistory

        data class Waiting(
            override val startedAt: Instant,
            override val waitingSince: Instant,
            override val issue: PathActionIssue,
        ) : State, Operation.State.Waiting {
            override val reason: CaString get() = issue.title
        }

        data class Completed(
            override val startedAt: Instant,
            override val completedAt: Instant = Clock.System.now(),
            override val error: Throwable? = null,
            override val report: SaveFilesReport,
        ) : State, Operation.State.Completed, Operation.HasPerformanceHistory {
            override val summary: CaString get() = report.summary
            override val performanceHistory: PerformanceHistory? get() = report.performanceHistory
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, command: Command): SaveFilesOperation
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}

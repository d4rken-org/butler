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

            // Check for conflicts
            if (gatewaySwitch.exists(targetPath)) {
                val resolvedPath = handleConflict(
                    source = source,
                    targetPath = targetPath,
                    targetDirectory = targetDirectory,
                    issueResolver = issueResolver,
                )
                if (resolvedPath == null) {
                    inputStream.close()
                    return SaveFilesReport.FileResult.Skipped(
                        filename = source.filename,
                        reason = SaveFilesReport.FileResult.Skipped.SkipReason.CONFLICT,
                    )
                }
                targetPath = resolvedPath
            }

            // Create file and write with progress tracking
            gatewaySwitch.createFile(targetPath, createParents = false)

            var bytesWritten = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            gatewaySwitch.openOutputStream(targetPath, append = false).use { outputStream ->
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

    private suspend fun handleConflict(
        source: Command.SourceFile,
        targetPath: APath<*>,
        targetDirectory: APath<*>,
        issueResolver: PathOperationIssueResolver,
    ): APath<*>? {
        // Check "apply to all" flags first
        when {
            issueResolver.skipAllPathExists -> {
                log(tag, INFO) { "Skipping conflict (apply-to-all): ${source.filename}" }
                return null
            }
            issueResolver.overwriteAllPathExists -> {
                log(tag, INFO) { "Overwriting (apply-to-all): ${source.filename}" }
                deleteForOverwrite(targetPath)
                return targetPath
            }
            issueResolver.renameSourceAllPathExists -> {
                val uniqueName = generateUniqueName(targetDirectory, source.filename)
                log(tag, INFO) { "Auto-renaming (apply-to-all): ${source.filename} -> $uniqueName" }
                return targetDirectory.child(uniqueName)
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
                null
            }

            is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> {
                log(tag, INFO) { "User chose to overwrite: ${source.filename}" }
                deleteForOverwrite(targetPath)
                targetPath
            }

            is PathActionIssue.PathAlreadyExists.Resolution.RenameSource -> {
                log(tag, INFO) { "User chose to rename: ${source.filename} -> ${resolution.newName}" }
                targetDirectory.child(resolution.newName)
            }

            is PathActionIssue.PathAlreadyExists.Resolution.RenameDestination -> {
                log(tag, INFO) { "User chose to rename existing: ${targetPath.name} -> ${resolution.newName}" }
                val newDestPath = targetDirectory.child(resolution.newName)
                if (gatewaySwitch.move(targetPath, newDestPath) !is MoveOutcome.Moved) {
                    // Writing to targetPath without a successful rename would collide with the existing file
                    throw WriteException("Could not rename existing file to ${resolution.newName}", targetPath)
                }
                targetPath
            }

            else -> targetPath
        }
    }

    private suspend fun deleteForOverwrite(targetPath: APath<*>) {
        if (!gatewaySwitch.delete(targetPath, recursive = false)) {
            throw WriteException("Could not delete existing file for overwrite", targetPath)
        }
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
            count = Progress.Count.Counter(
                current = snapshot.itemsProcessed + 1,
                max = snapshot.totalItems,
            ),
            extra = perfHistory,
        ).let { progress ->
            metrics.overall?.let { progress.copy(secondary = it) } ?: progress
        }

        val secondaryProgress = Progress.Data(
            primary = currentSource.filename.toCaString(),
            count = Progress.Count.Size(
                current = snapshot.currentFileBytes,
                max = snapshot.currentFileSize,
            ),
        ).let { progress ->
            metrics.currentFile?.let { progress.copy(secondary = it) } ?: progress
        }

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

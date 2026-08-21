package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Download
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Explicit, user-initiated copy of a single (archive) file to local storage so it becomes
 * browsable via random access. This is the consent-based replacement for the removed implicit
 * container materialization: known size up front, free-space precheck, visible progress, and an
 * atomic temp-sibling commit so cancellation or failure never leaves a truncated file.
 */
class DownloadLocalCopyOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.DownloadLocalCopy,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "DownloadLocalCopy")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Download
        override val title = R.string.explorer_operation_download_copy_title.toCaString()
        override val description = caString { cx ->
            cx.getString(
                R.string.explorer_operation_download_copy_description,
                command.source.name,
                command.destinationDir.userReadablePath.get(cx),
            )
        }
        override val kind = Operation.Metadata.Kind.COPY
        override val intendedPaths = listOf(command.source, command.destinationDir)
    }

    override fun perform(operationContext: Operation.Context): Flow<State> = channelFlow {
        log(tag) { "perform(): $command" }
        var stateActive = State.Active(startedAt = operationContext.startedAt)
        send(stateActive)

        val sourceLookup = gatewaySwitch.lookup(command.source, LookupOptions(fetchSize = true))
        val sourceSize = sourceLookup.size

        // Reject only when both sides are known; unknown values fall through to the mid-copy
        // ENOSPC handling. The temp sibling means peak usage is one copy, not two.
        val freeSpace = runCatching { gatewaySwitch.getFileSystem(command.destinationDir).freeSpace }.getOrNull()
        if (sourceSize != null && freeSpace != null && sourceSize > freeSpace) {
            throw WriteException("Not enough free space for a ${sourceSize}B copy", command.destinationDir)
        }

        gatewaySwitch.createDir(command.destinationDir, createParents = true)
        val destPath = command.destinationDir.child(command.source.name)
        var overwriteAuthorized = false
        if (gatewaySwitch.exists(destPath)) {
            val resolution = issueHandler.handleIssue(
                operationContext.id,
                PathActionIssue.PathAlreadyExists(
                    destination = gatewaySwitch.lookup(destPath, LookupOptions()),
                    canSkip = false,
                    canOverwrite = true,
                ),
            )
            when (resolution) {
                is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> overwriteAuthorized = true
                else -> {
                    log(tag, INFO) { "Conflict prompt dismissed, aborting download" }
                    send(
                        State.Completed(
                            startedAt = operationContext.startedAt,
                            report = CopyOperationReport.Builder().build(),
                        ),
                    )
                    return@channelFlow
                }
            }
        }

        val tempPath = command.destinationDir.child(".${destPath.name}.${Uuid.random().toString().take(8)}.part")
        // Once the pre-existing destination is deleted, the temp is the only surviving copy and
        // must be kept on any later failure; before that boundary it is a discardable orphan.
        var destructiveBoundaryCrossed = false
        val written = try {
            gatewaySwitch.openInputStream(command.source).use { input ->
                gatewaySwitch.openOutputStream(tempPath).use { output ->
                    var count = 0L
                    var lastProgressAt = 0L
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        count += read
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                            lastProgressAt = now
                            stateActive = stateActive.copy(
                                primaryProgress = Progress.Data(
                                    primary = command.source.userReadableName,
                                    count = sourceSize
                                        ?.let { Progress.Count.Size(current = count, max = it) }
                                        ?: Progress.Count.Indeterminate(),
                                ),
                            )
                            send(stateActive)
                        }
                    }
                    output.flush()
                    count
                }
            }.also {
                if (gatewaySwitch.exists(destPath)) {
                    if (!overwriteAuthorized) {
                        // Appeared after the conflict check - never delete what the user
                        // didn't authorize us to replace.
                        throw WriteException("Destination appeared during download", destPath)
                    }
                    if (!gatewaySwitch.delete(destPath)) {
                        throw WriteException("Could not replace existing file", destPath)
                    }
                    destructiveBoundaryCrossed = true
                }
                if (gatewaySwitch.move(tempPath, destPath) !is MoveOutcome.Moved || !gatewaySwitch.exists(destPath)) {
                    val kept = if (destructiveBoundaryCrossed) ", data kept as ${tempPath.name}" else ""
                    throw WriteException("Could not finalize downloaded copy$kept", destPath)
                }
            }
        } catch (e: Exception) {
            log(tag, WARN) { "Download failed: ${e.asLog()}" }
            if (!destructiveBoundaryCrossed) {
                withContext(NonCancellable) {
                    runCatching { if (gatewaySwitch.exists(tempPath)) gatewaySwitch.delete(tempPath) }
                }
            }
            throw e
        }

        val destLookup = gatewaySwitch.lookup(destPath, LookupOptions(fetchSize = true))
        fileSystemHinter.trackPathsAdded(operationContext.id, listOf(destLookup))

        val report = CopyOperationReport.Builder().apply {
            addCopiedItems(listOf(destLookup))
            setCopiedBytes(written)
        }.build()
        send(State.Completed(startedAt = operationContext.startedAt, report = report))
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.DownloadLocalCopy,
        ): DownloadLocalCopyOperation
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}

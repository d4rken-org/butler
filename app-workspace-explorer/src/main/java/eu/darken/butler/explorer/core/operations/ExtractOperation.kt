package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Unarchive
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
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ExtractOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Extract,
    private val issueHandler: IssueHandler,
    private val gatewaySwitch: GatewaySwitch,
    private val archiveService: ArchiveService,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Extract")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Unarchive
        override val title = R.string.explorer_operation_extract_title.toCaString()
        override val description = caString { cx ->
            cx.getString(
                R.string.explorer_operation_extract_description,
                command.archive.name,
                command.destinationDir.userReadablePath.get(cx),
            )
        }
        override val kind = Operation.Metadata.Kind.EXTRACT
        override val intendedPaths = listOf(command.archive, command.destinationDir)
    }

    override fun perform(operationContext: Operation.Context): Flow<State> = channelFlow {
        log(tag) { "perform(): $command" }
        var stateActive = State.Active(startedAt = operationContext.startedAt)
        send(stateActive)

        val index = archiveService.index(command.archive)
        val wanted = index.entriesBySegments.values.filter { meta ->
            if (meta.isDirectory || meta.isSymlink) return@filter false
            command.entries?.any { requested -> meta.segments.startsWithList(requested) } ?: true
        }

        // Prompt for the password up front so we never write a half-decrypted tree.
        var attemptFailed = false
        while (archiveService.requiresPassword(command.archive)) {
            val resolution = issueHandler.handleIssue(
                operationContext.id,
                PathActionIssue.ArchivePasswordRequired(
                    container = command.archive,
                    attemptFailed = attemptFailed,
                ),
            )
            if (resolution !is PathActionIssue.ArchivePasswordRequired.Resolution.Submit) {
                log(tag, INFO) { "Password prompt dismissed, aborting extract" }
                send(State.Completed(startedAt = operationContext.startedAt, report = ExtractOperationReport.Builder().build()))
                return@channelFlow
            }
            attemptFailed = true // any subsequent loop means the previous attempt didn't verify
        }

        // Whole-archive extraction lands in an archive-named subdirectory; selection extraction
        // writes entries at their in-archive paths directly under the destination.
        val baseDir = if (command.entries == null) {
            // Fall back to the full name when the archive name has no stem (e.g. ".zip").
            val stem = command.archive.name.substringBeforeLast('.').ifBlank { command.archive.name }
            command.destinationDir.child(stem)
        } else {
            command.destinationDir
        }
        gatewaySwitch.createDir(baseDir, createParents = true)
        // Canonical base used to reject entries whose real (symlink-resolved) parent escapes the
        // destination - lexical segment sanitization alone can't catch a pre-existing symlink at the
        // destination. For SAF/root this is effectively identity (no symlinks), so it just passes through.
        val canonicalBase = runCatching { gatewaySwitch.canonicalize(baseDir) }.getOrDefault(baseDir)

        val reportBuilder = ExtractOperationReport.Builder()
        val addedLookups = mutableListOf<eu.darken.butler.common.files.APathLookup<*>>()
        var processedBytes = 0L

        archiveService.useEntryStreams(command.archive, wanted) { meta, input ->
            currentCoroutineContext().ensureActive()
            val destPath = baseDir.child(*meta.segments.toTypedArray())
            val destParent = destPath.parent
            destParent?.let { gatewaySwitch.createDir(it, createParents = true) }

            // Reject a destination whose real parent resolved outside the extraction root (zip-slip
            // through an existing symlink).
            val canonicalParent = destParent?.let { runCatching { gatewaySwitch.canonicalize(it) }.getOrNull() }
            if (canonicalParent != null && !canonicalParent.isDescendantOfOrSelf(canonicalBase)) {
                log(tag, WARN) { "Refusing entry that escapes destination: ${meta.segments.joinToString("/")}" }
                reportBuilder.addSkipped(meta.segments.joinToString("/"))
                return@useEntryStreams
            }

            if (gatewaySwitch.exists(destPath)) {
                val destLookup = gatewaySwitch.lookup(destPath, LookupOptions())
                val resolution = issueHandler.handleIssue(
                    operationContext.id,
                    PathActionIssue.PathAlreadyExists(
                        destination = destLookup,
                        canSkip = true,
                        canOverwrite = true,
                    ),
                )
                when (resolution) {
                    is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> Unit
                    else -> {
                        reportBuilder.addSkipped(meta.segments.joinToString("/"))
                        return@useEntryStreams
                    }
                }
            }

            // Write to a temp sibling and commit on success so an interrupted extract never
            // leaves a truncated file at the destination. A random token keeps the temp name from
            // ever colliding with a real archive entry or a user's file (which we'd otherwise delete).
            val tempPath = destParent!!.child(".${destPath.name}.${Uuid.random().toString().take(8)}.part")
            try {
                val written = gatewaySwitch.openOutputStream(tempPath).use { output ->
                    var count = 0L
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        count += read
                    }
                    output.flush()
                    count
                }
                if (gatewaySwitch.exists(destPath)) gatewaySwitch.delete(destPath)
                gatewaySwitch.move(tempPath, destPath)
                processedBytes += written
                reportBuilder.addExtracted(destPath, written)
                runCatching { gatewaySwitch.lookup(destPath, LookupOptions()) }.getOrNull()?.let { addedLookups.add(it) }
            } catch (e: Exception) {
                runCatching { if (gatewaySwitch.exists(tempPath)) gatewaySwitch.delete(tempPath) }
                throw e
            }

            stateActive = stateActive.copy(
                primaryProgress = eu.darken.butler.common.progress.Progress.Data(
                    primary = destPath.userReadableName,
                    count = eu.darken.butler.common.progress.Progress.Count.Size(
                        current = processedBytes,
                        max = index.entriesBySegments.values.sumOf { it.size ?: 0L },
                    ),
                ),
            )
            send(stateActive)
        }

        val report = reportBuilder.build()
        fileSystemHinter.trackPathsAdded(operationContext.id, addedLookups)
        send(State.Completed(startedAt = operationContext.startedAt, report = report))
    }

    private fun List<String>.startsWithList(prefix: List<String>): Boolean =
        size >= prefix.size && subList(0, prefix.size) == prefix

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, command: ExplorerCommand.Extract): ExtractOperation
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}

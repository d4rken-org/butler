package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Archive
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.extensions.crumbsTo
import eu.darken.butler.common.files.extensions.isAncestorOfOrSelf
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlin.uuid.Uuid

class CompressOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Compress,
    private val gatewaySwitch: GatewaySwitch,
    private val archiveService: ArchiveService,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Compress")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Archive
        override val title = R.string.explorer_operation_compress_title.toCaString()
        override val description = caString { cx ->
            cx.getQuantityString2(
                R.plurals.explorer_operation_compress_description,
                command.sources.size,
                command.sources.size,
                command.archiveName,
            )
        }
        override val kind = Operation.Metadata.Kind.COMPRESS
        override val intendedPaths = command.sources + command.destinationDir
    }

    override fun perform(operationContext: Operation.Context): Flow<State> = channelFlow {
        log(tag) { "perform(): $command" }
        var stateActive = State.Active(startedAt = operationContext.startedAt)
        send(stateActive)

        val outputPath = command.destinationDir.child(command.archiveName)

        // Never let the growing archive become one of its own inputs.
        command.sources.forEach { source ->
            if (source.isAncestorOfOrSelf(outputPath)) {
                throw WriteException("Cannot write the archive inside a compressed source", outputPath)
            }
        }

        // Enumerate the flat list of write entries from the (possibly nested) sources.
        val entries = mutableListOf<ArchiveService.WriteEntry>()
        for (source in command.sources) {
            val sourceLookup = gatewaySwitch.lookup(source, LookupOptions(fetchSize = true))
            if (!sourceLookup.isDirectory) {
                entries += ArchiveService.WriteEntry(source.name, source, isDirectory = false, size = sourceLookup.size)
                continue
            }
            entries += ArchiveService.WriteEntry(source.name, source, isDirectory = true, size = null)
            // Fail fast on unreadable subtrees: an archive silently missing entries is worse
            // than a failed compression.
            val descendants = gatewaySwitch
                .walk(
                    source,
                    LookupOptions(fetchSize = true),
                    APathGateway.WalkOptions(onError = { _, _ -> false }),
                )
                .toList()
            descendants.forEach { lookup ->
                val relative = source.crumbsTo(lookup.lookedUp)
                val entryName = (listOf(source.name) + relative).joinToString("/")
                entries += ArchiveService.WriteEntry(
                    name = entryName,
                    source = lookup.lookedUp,
                    isDirectory = lookup.isDirectory,
                    size = lookup.size,
                )
            }
        }

        val fileCount = entries.count { !it.isDirectory }
        val reportBuilder = CompressOperationReport.Builder(outputPath)

        // Write to a temp sibling, commit on success. Random token so the temp name can't collide
        // with an existing user file that we'd then delete.
        val tempPath = command.destinationDir.child(".${command.archiveName}.${Uuid.random().toString().take(8)}.part")
        try {
            var processed = 0
            archiveService.compress(command.format, tempPath, entries) { entry, _ ->
                if (!entry.isDirectory) {
                    processed++
                    reportBuilder.addCompressedFile()
                }
                stateActive = stateActive.copy(
                    primaryProgress = Progress.Data(
                        primary = entry.name.toCaString(),
                        count = Progress.Count.Counter(current = processed.toLong(), max = fileCount.toLong()),
                    ),
                )
                trySend(stateActive)
            }
            if (gatewaySwitch.exists(outputPath)) gatewaySwitch.delete(outputPath)
            gatewaySwitch.move(tempPath, outputPath)
        } catch (e: Exception) {
            runCatching { if (gatewaySwitch.exists(tempPath)) gatewaySwitch.delete(tempPath) }
            throw e
        }

        val outputLookup = runCatching {
            gatewaySwitch.lookup(outputPath, LookupOptions(fetchSize = true))
        }.getOrNull()
        reportBuilder.setOutputBytes(outputLookup?.size ?: 0L)

        outputLookup?.let { fileSystemHinter.trackPathsAdded(operationContext.id, listOf(it)) }
        send(State.Completed(startedAt = operationContext.startedAt, report = reportBuilder.build()))
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, command: ExplorerCommand.Compress): CompressOperation
    }
}

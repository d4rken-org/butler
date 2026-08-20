package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Compress
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
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.archive.ArchivePasswordStore
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.archive.ArchiveWriteOptions
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.extensions.crumbsTo
import eu.darken.butler.common.files.extensions.isAncestorOfOrSelf
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

class CompressOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Compress,
    private val gatewaySwitch: GatewaySwitch,
    private val archiveService: ArchiveService,
    private val archivePasswordStore: ArchivePasswordStore,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Compress")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Compress
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
        try {
            performGuarded(operationContext)
        } finally {
            // Deterministic wipe for every path where perform() actually runs (success, error,
            // mid-run cancellation). The queued-cancel path is covered by onDiscarded().
            command.options.password?.fill(Char(0))
        }
    }

    /**
     * Safety net for the case where [perform] never runs (operation cancelled while queued):
     * [ManagedOperation] invokes this so the password buffer is still wiped. Idempotent.
     */
    override fun onDiscarded() {
        command.options.password?.fill(Char(0))
    }

    private suspend fun ProducerScope<State>.performGuarded(
        operationContext: Operation.Context,
    ) {
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
            val writeOptions = ArchiveWriteOptions(
                format = command.format,
                preset = command.options.preset,
                password = command.options.password,
            )
            archiveService.compress(writeOptions, tempPath, entries) { entry, _ ->
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
        } catch (e: Exception) {
            // Cleanup runs even though the coroutine may already be cancelled.
            withContext(NonCancellable) {
                runCatching { if (gatewaySwitch.exists(tempPath)) gatewaySwitch.delete(tempPath) }
            }
            throw e
        }

        // Commit, serialized per output path so concurrent compresses to the same name can't
        // interleave their delete/move/seed steps.
        //
        // Once a pre-existing archive is deleted the temp may be the only surviving copy, so it must
        // be kept on any later failure. Before that boundary — including while awaiting the lock or on
        // the "target already exists, not confirmed" abort — the temp is a discardable orphan.
        var destructiveBoundaryCrossed = false
        try {
            archiveService.withOutputCommitLock(outputPath) {
                if (gatewaySwitch.exists(outputPath)) {
                    if (!command.overwriteConfirmed) {
                        // Target appeared after the pre-check (or the pre-check errored); abort without
                        // touching the existing archive.
                        throw WriteException("Archive already exists", outputPath)
                    }
                    if (!gatewaySwitch.delete(outputPath)) {
                        throw WriteException("Could not replace existing archive, data kept as ${tempPath.name}", outputPath)
                    }
                    destructiveBoundaryCrossed = true
                }
                if (gatewaySwitch.move(tempPath, outputPath) !is MoveOutcome.Moved || !gatewaySwitch.exists(outputPath)) {
                    throw WriteException("Could not finalize archive, data kept as ${tempPath.name}", outputPath)
                }

                // Post-commit bookkeeping is one consistent step even if the operation is cancelled now.
                withContext(NonCancellable) {
                    archiveService.invalidate(outputPath)
                    val password = command.options.password
                    if (password != null) {
                        // Let the user browse/extract their fresh archive without a reprompt this session.
                        archivePasswordStore.set(outputPath, password)
                    } else {
                        // A plain archive may have replaced an encrypted one of the same name.
                        archivePasswordStore.evict(outputPath)
                    }
                }
            }
        } catch (e: Throwable) {
            if (!destructiveBoundaryCrossed) {
                withContext(NonCancellable) {
                    runCatching { if (gatewaySwitch.exists(tempPath)) gatewaySwitch.delete(tempPath) }
                }
            }
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

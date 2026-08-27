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
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.ArchiveIndex
import eu.darken.butler.common.files.archive.ArchiveNotSeekableException
import eu.darken.butler.common.files.archive.ArchivePasswordRequiredException
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.archive.SequentialOutcome
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
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
        override val pathPlan = OperationPathPlan(
            targets = listOf(command.archive),
            destination = OperationPathPlan.Destination.Container(command.destinationDir),
        )
    }

    override fun perform(operationContext: Operation.Context): Flow<State> = channelFlow {
        log(tag) { "perform(): $command" }
        send(State.Active(startedAt = operationContext.startedAt))

        val index = try {
            archiveService.index(command.archive)
        } catch (e: ArchiveNotSeekableException) {
            log(tag, INFO) { "Container is not seekable, extracting sequentially" }
            null
        }
        if (index != null) performIndexed(operationContext, index) else performSequential(operationContext)
    }

    private suspend fun ProducerScope<State>.performIndexed(ctx: Operation.Context, index: ArchiveIndex) {
        var stateActive = State.Active(startedAt = ctx.startedAt)
        val wanted = index.entriesBySegments.values.filter { meta ->
            if (meta.isDirectory || meta.isSymlink) return@filter false
            command.entries?.any { requested -> meta.segments.startsWithList(requested) } ?: true
        }

        // Prompt for the password up front so we never write a half-decrypted tree.
        var attemptFailed = false
        while (archiveService.requiresPassword(command.archive)) {
            val resolution = issueHandler.handleIssue(
                ctx.id,
                PathActionIssue.ArchivePasswordRequired(
                    container = command.archive,
                    attemptFailed = attemptFailed,
                ),
            )
            if (resolution !is PathActionIssue.ArchivePasswordRequired.Resolution.Submit) {
                log(tag, INFO) { "Password prompt dismissed, aborting extract" }
                send(
                    State.Completed(
                        startedAt = ctx.startedAt,
                        report = ExtractOperationReport.Builder(command.archive).build(),
                    ),
                )
                return
            }
            attemptFailed = true // any subsequent loop means the previous attempt didn't verify
        }

        val session = createSession(command.entries == null)
        var processedBytes = 0L
        val totalBytes = wanted.sumOf { it.size ?: 0L }

        try {
            archiveService.useEntryStreams(command.archive, wanted) { meta, input ->
                currentCoroutineContext().ensureActive()
                val outcome = writeEntry(session, meta.segments, input) { existing ->
                    val resolution = issueHandler.handleIssue(
                        ctx.id,
                        PathActionIssue.PathAlreadyExists(
                            destination = existing,
                            canSkip = true,
                            canOverwrite = true,
                        ),
                    )
                    when (resolution) {
                        is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> ConflictDecision.OVERWRITE
                        is PathActionIssue.PathAlreadyExists.Resolution.Cancel ->
                            throw CancellationException("User cancelled")
                        else -> ConflictDecision.SKIP
                    }
                }
                if (outcome !is WriteOutcome.Written) return@useEntryStreams
                session.reportBuilder.addExtracted(outcome.destPath, outcome.bytes)
                outcome.lookup?.let { session.addedLookups.add(it) }

                processedBytes += outcome.bytes
                stateActive = stateActive.copy(
                    primaryProgress = Progress.Data(
                        primary = outcome.destPath.userReadableName,
                        count = Progress.Count.Size(current = processedBytes, max = totalBytes),
                    ),
                )
                send(stateActive)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Extraction failed: ${e.asLog()}" }
            finish(ctx, session, error = e)
            return
        }
        finish(ctx, session, error = null)
    }

    private suspend fun ProducerScope<State>.performSequential(ctx: Operation.Context) {
        if (command.entries != null) {
            // Selection requires browsing, browsing requires an index - unreachable via regular UI.
            throw ReadException("Cannot extract a selection from a non-seekable archive", command.archive)
        }
        var stateActive = State.Active(startedAt = ctx.startedAt)
        val baseDir = command.destinationDir.child(ArchiveFormat.stemOf(command.archive.name))

        // Pre-existing collisions are only possible below an existing base dir. Deciding the policy
        // once, before the container stream opens, keeps suspending prompts away from a live (and
        // possibly fragile) forward-only stream.
        var policy = ConflictDecision.SKIP
        if (gatewaySwitch.exists(baseDir)) {
            val existing = gatewaySwitch.lookup(baseDir, LookupOptions())
            val resolution = issueHandler.handleIssue(
                ctx.id,
                PathActionIssue.PathAlreadyExists(
                    destination = existing,
                    canSkip = true,
                    canOverwrite = true,
                ),
            )
            policy = when (resolution) {
                is PathActionIssue.PathAlreadyExists.Resolution.Overwrite -> ConflictDecision.OVERWRITE
                is PathActionIssue.PathAlreadyExists.Resolution.Skip -> ConflictDecision.SKIP
                else -> {
                    log(tag, INFO) { "Merge prompt dismissed, aborting sequential extract" }
                    send(
                    State.Completed(
                        startedAt = ctx.startedAt,
                        report = ExtractOperationReport.Builder(command.archive).build(),
                    ),
                )
                    return
                }
            }
            // A regular file (or symlink) at the base path can't be merged under: Overwrite
            // replaces it with a directory, Skip has nothing it could meaningfully merge.
            if (!existing.isDirectory) {
                if (policy == ConflictDecision.OVERWRITE) {
                    if (!gatewaySwitch.delete(baseDir)) {
                        throw WriteException("Failed to replace existing file", baseDir)
                    }
                } else {
                    log(tag, INFO) { "Base path is a file and merge was declined, aborting" }
                    send(
                    State.Completed(
                        startedAt = ctx.startedAt,
                        report = ExtractOperationReport.Builder(command.archive).build(),
                    ),
                )
                    return
                }
            }
        }

        val session = createSession(wholeArchive = true)

        // Same-run collision bookkeeping, mirroring the index rules the seekable path gets for
        // free from buildIndexMaps: directories/children win over files, duplicate files last-wins.
        // Successful writes are recorded here (keyed by segments, superseding duplicates) and only
        // folded into the report at the end, so replaced/shadow-deleted files never show up in it.
        val finalEntries = LinkedHashMap<List<String>, WriteOutcome.Written>()
        val selfDirs = mutableSetOf<List<String>>()
        var resultsFolded = false
        fun foldResults() {
            if (resultsFolded) return
            resultsFolded = true
            finalEntries.values.forEach { written ->
                session.reportBuilder.addExtracted(written.destPath, written.bytes)
                written.lookup?.let { session.addedLookups.add(it) }
            }
        }
        // Ordinals handled across all passes; raw names may repeat, ordinals are the identity.
        val processed = mutableSetOf<Int>()
        val expectedFingerprint = archiveService.statContainer(command.archive).fingerprint

        var currentEntry: String? = null
        var lastProgressAt = 0L
        val onProgress: (Long, Long?) -> Unit = { read, total ->
            val now = System.currentTimeMillis()
            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                lastProgressAt = now
                trySend(
                    stateActive.copy(
                        primaryProgress = Progress.Data(
                            primary = (currentEntry ?: command.archive.name).toCaString(),
                            count = total
                                ?.let { Progress.Count.Size(current = read, max = it) }
                                ?: Progress.Count.Indeterminate(),
                        ),
                    ),
                )
            }
        }

        try {
            var attemptFailed = false
            while (true) {
                try {
                    archiveService.extractZipSequential(
                        container = command.archive,
                        processedOrdinals = processed.toSet(),
                        expectedFingerprint = expectedFingerprint,
                        onContainerProgress = onProgress,
                    ) { entry, input ->
                        currentCoroutineContext().ensureActive()
                        currentEntry = entry.rawName

                        // A directory created by an earlier entry's children wins over this file.
                        if (entry.segments in selfDirs) {
                            session.reportBuilder.addSkipped(entry.segments.joinToString("/"))
                            processed += entry.ordinal
                            return@extractZipSequential SequentialOutcome.SKIPPED_COLLISION
                        }
                        // A file written earlier this run shadows a directory needed now: children
                        // win, but only mark the path as a directory if the file actually went away.
                        for (depth in 1 until entry.segments.size) {
                            val prefix = entry.segments.subList(0, depth)
                            val shadowing = finalEntries[prefix] ?: continue
                            val deleted = try {
                                gatewaySwitch.delete(shadowing.destPath)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                false
                            }
                            if (deleted) {
                                finalEntries.remove(prefix)
                                selfDirs += prefix.toList()
                            } else {
                                log(tag, WARN) { "Failed to remove file shadowing a directory: ${shadowing.destPath}" }
                            }
                        }

                        val selfDuplicate = entry.segments in finalEntries
                        val outcome = writeEntry(session, entry.segments, input) {
                            // Own earlier write: replace silently (last-wins); anything else was
                            // there before this operation and follows the up-front policy.
                            if (selfDuplicate) ConflictDecision.OVERWRITE else policy
                        }
                        processed += entry.ordinal
                        when (outcome) {
                            is WriteOutcome.Written -> {
                                finalEntries[entry.segments] = outcome
                                for (depth in 1 until entry.segments.size) {
                                    selfDirs += entry.segments.subList(0, depth).toList()
                                }
                                SequentialOutcome.EXTRACTED
                            }
                            WriteOutcome.Skipped -> SequentialOutcome.SKIPPED_POLICY
                        }
                    }
                    break
                } catch (e: ArchivePasswordRequiredException) {
                    attemptFailed = attemptFailed || e.attemptFailed
                    val resolution = issueHandler.handleIssue(
                        ctx.id,
                        PathActionIssue.ArchivePasswordRequired(
                            container = command.archive,
                            attemptFailed = attemptFailed,
                        ),
                    )
                    if (resolution !is PathActionIssue.ArchivePasswordRequired.Resolution.Submit) {
                        log(tag, INFO) { "Password prompt dismissed, aborting sequential extract" }
                        foldResults()
                        finish(ctx, session, error = null)
                        return
                    }
                    // Restart: the resolved password was cached by the issue handler; committed
                    // ordinals are drained but not re-delivered.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Sequential extraction failed: ${e.asLog()}" }
            foldResults()
            finish(ctx, session, error = e)
            return
        }
        foldResults()
        finish(ctx, session, error = null)
    }

    private suspend fun createSession(wholeArchive: Boolean): ExtractSession {
        // Whole-archive extraction lands in an archive-named subdirectory; selection extraction
        // writes entries at their in-archive paths directly under the destination.
        val baseDir = if (wholeArchive) {
            command.destinationDir.child(ArchiveFormat.stemOf(command.archive.name))
        } else {
            command.destinationDir
        }
        gatewaySwitch.createDir(baseDir, createParents = true)
        // Canonical base used to reject entries whose real (symlink-resolved) parent escapes the
        // destination - lexical segment sanitization alone can't catch a pre-existing symlink at the
        // destination. For SAF/root this is effectively identity (no symlinks), so it just passes through.
        val canonicalBase = runCatching { gatewaySwitch.canonicalize(baseDir) }.getOrDefault(baseDir)
        return ExtractSession(
            baseDir = baseDir,
            canonicalBase = canonicalBase,
            reportBuilder = ExtractOperationReport.Builder(command.archive),
        )
    }

    private suspend fun ProducerScope<State>.finish(
        ctx: Operation.Context,
        session: ExtractSession,
        error: Throwable?,
    ) {
        fileSystemHinter.trackPathsAdded(ctx.id, session.addedLookups)
        send(
            State.Completed(
                startedAt = ctx.startedAt,
                error = error,
                report = session.reportBuilder.build(),
            ),
        )
    }

    private class ExtractSession(
        val baseDir: APath<*>,
        val canonicalBase: APath<*>,
        val reportBuilder: ExtractOperationReport.Builder,
        val addedLookups: MutableList<APathLookup<*>> = mutableListOf(),
    )

    private enum class ConflictDecision { OVERWRITE, SKIP }

    private sealed interface WriteOutcome {
        data class Written(
            val destPath: APath<*>,
            val bytes: Long,
            val lookup: APathLookup<*>?,
        ) : WriteOutcome

        data object Skipped : WriteOutcome
    }

    /**
     * Writes one entry below the session base dir: parent creation, escape guard, conflict
     * consultation for pre-existing paths, temp-sibling write with atomic move. Skips are
     * reported on the session's report builder before returning; successful writes are NOT -
     * callers record them (the sequential branch must be able to supersede earlier writes).
     */
    private suspend fun writeEntry(
        session: ExtractSession,
        segments: List<String>,
        input: InputStream,
        onExistingConflict: suspend (APathLookup<*>) -> ConflictDecision,
    ): WriteOutcome {
        val destPath = session.baseDir.child(*segments.toTypedArray())
        val destParent = destPath.parent
        destParent?.let { gatewaySwitch.createDir(it, createParents = true) }

        // Reject a destination whose real parent resolved outside the extraction root (zip-slip
        // through an existing symlink).
        val canonicalParent = destParent?.let { runCatching { gatewaySwitch.canonicalize(it) }.getOrNull() }
        if (canonicalParent != null && !canonicalParent.isDescendantOfOrSelf(session.canonicalBase)) {
            log(tag, WARN) { "Refusing entry that escapes destination: ${segments.joinToString("/")}" }
            session.reportBuilder.addSkipped(segments.joinToString("/"))
            return WriteOutcome.Skipped
        }

        var overwriteAuthorized = false
        if (gatewaySwitch.exists(destPath)) {
            val destLookup = gatewaySwitch.lookup(destPath, LookupOptions())
            when (onExistingConflict(destLookup)) {
                ConflictDecision.OVERWRITE -> overwriteAuthorized = true
                ConflictDecision.SKIP -> {
                    session.reportBuilder.addSkipped(segments.joinToString("/"))
                    return WriteOutcome.Skipped
                }
            }
        }

        // Write to a temp sibling and commit on success so an interrupted extract never
        // leaves a truncated file at the destination. A random token keeps the temp name from
        // ever colliding with a real archive entry or a user's file (which we'd otherwise delete).
        //
        // Once a pre-existing destination is deleted the temp may be the only surviving copy of
        // that user file's replacement, so it must be kept on any later failure. Before that
        // boundary the temp is a discardable orphan (the archive still has the data).
        val tempPath = destParent!!.child(".${destPath.name}.${Uuid.random().toString().take(8)}.part")
        var destructiveBoundaryCrossed = false
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
            // Commit serialized per output path so concurrent operations on the same name
            // can't interleave their delete/move steps.
            archiveService.withOutputCommitLock(destPath) {
                if (gatewaySwitch.exists(destPath)) {
                    if (!overwriteAuthorized) {
                        // Appeared after the conflict check - never delete what the user
                        // didn't authorize us to replace.
                        throw WriteException("Destination appeared during extraction", destPath)
                    }
                    if (!gatewaySwitch.delete(destPath)) {
                        throw WriteException("Could not replace existing file", destPath)
                    }
                    destructiveBoundaryCrossed = true
                }
                if (gatewaySwitch.move(tempPath, destPath) !is MoveOutcome.Moved || !gatewaySwitch.exists(destPath)) {
                    val kept = if (destructiveBoundaryCrossed) ", data kept as ${tempPath.name}" else ""
                    throw WriteException("Could not finalize extracted file$kept", destPath)
                }
            }
            val lookup = runCatching { gatewaySwitch.lookup(destPath, LookupOptions()) }.getOrNull()
            return WriteOutcome.Written(destPath = destPath, bytes = written, lookup = lookup)
        } catch (e: Exception) {
            if (!destructiveBoundaryCrossed) {
                // Cleanup runs even though the coroutine may already be cancelled.
                withContext(NonCancellable) {
                    runCatching { if (gatewaySwitch.exists(tempPath)) gatewaySwitch.delete(tempPath) }
                }
            }
            throw e
        }
    }

    private fun List<String>.startsWithList(prefix: List<String>): Boolean =
        size >= prefix.size && subList(0, prefix.size) == prefix

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, command: ExplorerCommand.Extract): ExtractOperation
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}

package eu.darken.butler.common.files.write

import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.exists
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.use
import kotlin.uuid.Uuid

/**
 * What a writer is handed to produce the new content.
 *
 * [openOriginalSource] serves the target's PRE-COMMIT bytes for the whole duration of the commit,
 * whichever replacement strategy is in play - the writer never has to know where they come from.
 */
interface FileCommitContext {
    val sink: BufferedSink

    /** Positional source over the pre-commit content; stable until the commit finishes. */
    suspend fun openOriginalSource(offset: Long = 0L): Source
}

/**
 * A commit failed AFTER the target may have been mutated and could not be restored: the on-disk
 * state no longer reliably matches the pre-commit content.
 *
 * Callers that need their own (e.g. localized) type pass a factory to [AtomicFileWriter].
 */
open class AtomicWriteIntegrityException(
    message: String,
    cause: Throwable,
) : java.io.IOException(message, cause)

/**
 * A write that required an absent destination found one occupied at commit time. Only raised when
 * the caller passed `requireAbsent`, i.e. it has no permission to replace whatever is there.
 */
class AtomicWriteTargetExistsException(
    val target: APath<*>,
) : java.io.IOException("Write target appeared before the commit: ${target.path}")

/**
 * Atomically replaces a target file's content with whatever the writer streams into the provided
 * sink. Local paths use temp + rename swap (the target stays untouched and readable until the
 * point of no return); non-local (SAF) paths are overwritten in place behind a backup copy.
 *
 * The backup plays two roles depending on [OriginalAccess]:
 * - [OriginalAccess.FromTarget]: the writer splices the target's own pre-commit bytes (the
 *   in-editor save). In the in-place strategy those reads come FROM THE BACKUP, because the
 *   target itself is being truncated while the writer runs; in the temp-swap strategy they come
 *   from the untouched target.
 * - [OriginalAccess.None]: the writer never reads the target's old content (Save-As - content
 *   comes from another document). Any backup exists purely for rollback.
 */
class AtomicFileWriter(
    private val gatewaySwitch: GatewaySwitch,
    private val tag: String,
    /**
     * Builds the exception thrown when a commit fails and the original could not be restored.
     * Callers with their own error type (the editor forces a document reload off its own) override
     * this; everyone else gets [AtomicWriteIntegrityException].
     */
    private val integrityFailure: (String, Throwable) -> Throwable = { message, cause ->
        AtomicWriteIntegrityException(message, cause)
    },
) {

    sealed interface OriginalAccess {
        /** The writer reads the target's pre-commit bytes via [FileCommitContext.openOriginalSource]. */
        data object FromTarget : OriginalAccess

        /** The writer never reads the target's old content; calling openOriginalSource is a bug. */
        data object None : OriginalAccess
    }

    /**
     * @param requireAbsent the caller has permission to create [target] but not to replace anything
     *   found there. Re-checked inside the commit, because a caller's own pre-check cannot cover
     *   the window spent producing the content.
     */
    suspend fun replace(
        target: APath<*>,
        originalAccess: OriginalAccess,
        requireAbsent: Boolean = false,
        writer: suspend (FileCommitContext) -> Unit,
    ) {
        // Archive entries are read-only; reject before touching the gateway. The editor already opens
        // them read-only (canWrite=false), so this is a defensive backstop against a bypassed save gate.
        if (target is ArchivePath) {
            throw IllegalArgumentException("Archive entries are read-only and cannot be saved: $target")
        }

        // Unique per-save artifact names so we never collide with or delete a user's own
        // files, and never touch artifacts from a different (e.g. crashed) save. The existence
        // pre-check keeps a token collision from truncating or cleaning up a foreign file.
        val parent = target.parent
            ?: throw IllegalStateException("Cannot save - no parent directory")
        var tempPath: APath<*>
        var backupPath: APath<*>
        var attempts = 0
        do {
            check(attempts++ < 5) { "Could not find free artifact names for $target" }
            val token = Uuid.random().toString().take(8)
            tempPath = parent.child("${target.name}$TEMP_INFIX$token")
            backupPath = parent.child("${target.name}$BACKUP_INFIX$token")
        } while (tempPath.exists(gatewaySwitch) || backupPath.exists(gatewaySwitch))

        if (target is LocalPath) {
            replaceViaTempSwap(target, tempPath, backupPath, originalAccess, requireAbsent, writer)
        } else {
            replaceInPlace(target, backupPath, originalAccess, requireAbsent, writer)
        }
    }

    /**
     * Local-path streaming replace: the writer streams into a uniquely-named temp while the target
     * stays untouched and readable (cancellation-safe); the rename swap is the point of no return
     * and runs non-cancellable, restoring the target on failure.
     */
    suspend fun replaceViaTempSwap(
        target: APath<*>,
        tempPath: APath<*>,
        backupPath: APath<*>,
        originalAccess: OriginalAccess,
        requireAbsent: Boolean = false,
        writer: suspend (FileCommitContext) -> Unit,
    ) {
        try {
            writeContent(tempPath) { sink ->
                writer(contextFor(sink, originalAccess, readPath = target))
            }
        } catch (e: Exception) {
            cleanupArtifact(tempPath)
            throw e
        }

        // Producing the content above can take arbitrarily long, so the caller's pre-check is stale
        // by now. Checked here rather than inside the commit's reconciliation block: nothing has
        // been mutated yet, so this is a clean abort and not a failed commit.
        if (requireAbsent && target.exists(gatewaySwitch)) {
            cleanupArtifact(tempPath)
            throw AtomicWriteTargetExistsException(target)
        }

        withContext(NonCancellable) {
            var backedUp = false
            try {
                if (target.exists(gatewaySwitch)) {
                    check(gatewaySwitch.move(target, backupPath) is MoveOutcome.Moved) { "Backup move failed: $target -> $backupPath" }
                    backedUp = true
                }
                check(gatewaySwitch.move(tempPath, target) is MoveOutcome.Moved) { "Commit move failed: $tempPath -> $target" }
            } catch (e: Exception) {
                // An exception may still have moved documents (e.g. a lost IPC reply), so
                // reconcile from observable state instead of trusting the bookkeeping flag.
                // Failed observations are "unknown" (null), never "absent" — an unknown backup
                // state must funnel into the restore attempt, whose own failure is loud.
                val backupPresent = if (backedUp) true else runCatching { backupPath.exists(gatewaySwitch) }.getOrNull()
                val targetPresent = runCatching { target.exists(gatewaySwitch) }.getOrNull()
                var restored = backupPresent == false
                when {
                    backupPresent == false -> Unit // Provably nothing to restore

                    backupPresent == true && targetPresent == true -> {
                        // The commit landed despite the exception; keep the original as backup.
                        log(tag, WARN) { "Commit errored but target exists - original kept at $backupPath: ${e.asLog()}" }
                        restored = true
                    }

                    else -> try {
                        check(gatewaySwitch.move(backupPath, target) is MoveOutcome.Moved) { "Restore move returned false" }
                        restored = true
                    } catch (restoreError: Exception) {
                        log(tag, ERROR) { "Restore failed - original preserved at $backupPath: ${restoreError.asLog()}" }
                        e.addSuppressed(restoreError)
                    }
                }
                cleanupArtifact(tempPath)
                if (!restored) {
                    throw integrityFailure("Commit failed and the original could not be restored to $target", e)
                }
                throw e
            }
            cleanupArtifact(backupPath)
        }
    }

    /**
     * SAF/non-local streaming replace: an existing target is copied to a uniquely-named backup
     * first (cancellation-safe), then overwritten in place non-cancellable; the target is
     * restored from the backup on failure (backup retained if the restore also fails). A target
     * that didn't exist yet is created and deleted again on failure.
     */
    suspend fun replaceInPlace(
        target: APath<*>,
        backupPath: APath<*>,
        originalAccess: OriginalAccess,
        requireAbsent: Boolean = false,
        writer: suspend (FileCommitContext) -> Unit,
    ) {
        val targetExisted = target.exists(gatewaySwitch)
        if (originalAccess == OriginalAccess.FromTarget) {
            check(targetExisted) { "In-place splice requires an existing target: $target" }
        }
        if (requireAbsent && targetExisted) throw AtomicWriteTargetExistsException(target)
        var backupReady = false
        // Only a target THIS call brought into existence may be cleaned up on failure. Deriving that
        // from `targetExisted` instead would delete a file that appeared between the sample and the
        // create - one this call never owned.
        var createdTarget = false
        try {
            if (targetExisted) {
                gatewaySwitch.createFile(backupPath, createParents = false)
                writeContent(backupPath) { sink ->
                    gatewaySwitch.file(target, readWrite = false).use { handle ->
                        handle.source().buffer().use { source -> sink.writeAll(source) }
                    }
                }
                backupReady = true
            } else {
                gatewaySwitch.createFile(target, createParents = false)
                createdTarget = true
            }

            withContext(NonCancellable) {
                writeContent(target) { sink ->
                    writer(contextFor(sink, originalAccess, readPath = backupPath))
                }
            }
        } catch (e: Exception) {
            when {
                backupReady -> {
                    var restored = false
                    withContext(NonCancellable) {
                        runCatching {
                            writeContent(target) { sink ->
                                gatewaySwitch.file(backupPath, readWrite = false).use { handle ->
                                    handle.source().buffer().use { source -> sink.writeAll(source) }
                                }
                            }
                            restored = true
                        }.onFailure {
                            log(tag, ERROR) { "Restore failed - original preserved at $backupPath: ${it.asLog()}" }
                            e.addSuppressed(it)
                        }
                    }
                    if (!restored) {
                        throw integrityFailure("In-place commit failed and $target could not be restored", e)
                    }
                }
                // Target created by this call: partial writes are junk, nothing existed to restore
                createdTarget -> {
                    cleanupArtifact(target)
                    cleanupArtifact(backupPath)
                }
                // Backup copy itself failed; the target was never touched, the partial backup is junk
                else -> cleanupArtifact(backupPath)
            }
            throw e
        }

        cleanupArtifact(backupPath)
    }

    /** Streams [writer] output into [target] (truncated first), flushed to disk before returning. */
    private suspend fun writeContent(target: APath<*>, writer: suspend (BufferedSink) -> Unit) {
        gatewaySwitch.file(target, readWrite = true).use { handle ->
            handle.resize(0)
            handle.sink().buffer().use { sink ->
                writer(sink)
                sink.flush()
            }
            handle.flush()
        }
    }

    private fun contextFor(
        sink: BufferedSink,
        originalAccess: OriginalAccess,
        readPath: APath<*>,
    ): FileCommitContext = when (originalAccess) {
        OriginalAccess.FromTarget -> GatewayCommitContext(sink, readPath)
        OriginalAccess.None -> object : FileCommitContext {
            override val sink: BufferedSink = sink
            override suspend fun openOriginalSource(offset: Long): Source =
                throw IllegalStateException("Writer declared OriginalAccess.None but read the original")
        }
    }

    private inner class GatewayCommitContext(
        override val sink: BufferedSink,
        private val readPath: APath<*>,
    ) : FileCommitContext {
        override suspend fun openOriginalSource(offset: Long): Source {
            val handle = gatewaySwitch.file(readPath, readWrite = false)
            val source = handle.source(fileOffset = offset)
            return object : Source by source {
                override fun close() {
                    source.close()
                    handle.close()
                }
            }
        }
    }

    /** Best-effort removal of a save artifact; logs (never throws) if the delete fails or returns false. */
    private suspend fun cleanupArtifact(path: APath<*>) {
        runCatching {
            if (path.exists(gatewaySwitch) && !gatewaySwitch.delete(path)) {
                log(tag, WARN) { "Failed to delete leftover save artifact: $path" }
            }
        }.onFailure { log(tag, WARN) { "Error cleaning up save artifact $path: ${it.asLog()}" } }
    }

    companion object {
        const val TEMP_INFIX = ".butler-save-tmp-"
        const val BACKUP_INFIX = ".butler-save-bak-"
    }
}

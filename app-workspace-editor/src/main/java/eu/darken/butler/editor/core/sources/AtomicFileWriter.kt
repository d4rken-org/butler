package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
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
) {

    sealed interface OriginalAccess {
        /** The writer reads the target's pre-commit bytes via [EditorDataSource.CommitContext.openOriginalSource]. */
        data object FromTarget : OriginalAccess

        /** The writer never reads the target's old content; calling openOriginalSource is a bug. */
        data object None : OriginalAccess
    }

    suspend fun replace(
        target: APath<*>,
        originalAccess: OriginalAccess,
        writer: suspend (EditorDataSource.CommitContext) -> Unit,
    ) {
        // Archive entries are read-only; reject before touching the gateway. The editor already opens
        // them read-only (canWrite=false), so this is a defensive backstop against a bypassed save gate.
        if (target is eu.darken.butler.common.files.ArchivePath) {
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
            replaceViaTempSwap(target, tempPath, backupPath, originalAccess, writer)
        } else {
            replaceInPlace(target, backupPath, originalAccess, writer)
        }
    }

    /**
     * Local-path streaming replace: the writer streams into a uniquely-named temp while the target
     * stays untouched and readable (cancellation-safe); the rename swap is the point of no return
     * and runs non-cancellable, restoring the target on failure.
     */
    internal suspend fun replaceViaTempSwap(
        target: APath<*>,
        tempPath: APath<*>,
        backupPath: APath<*>,
        originalAccess: OriginalAccess,
        writer: suspend (EditorDataSource.CommitContext) -> Unit,
    ) {
        try {
            writeContent(tempPath) { sink ->
                writer(contextFor(sink, originalAccess, readPath = target))
            }
        } catch (e: Exception) {
            cleanupArtifact(tempPath)
            throw e
        }

        withContext(NonCancellable) {
            var backedUp = false
            try {
                if (target.exists(gatewaySwitch)) {
                    check(gatewaySwitch.move(target, backupPath)) { "Backup move failed: $target -> $backupPath" }
                    backedUp = true
                }
                check(gatewaySwitch.move(tempPath, target)) { "Commit move failed: $tempPath -> $target" }
            } catch (e: Exception) {
                // Moves are atomic, so a failure here means the commit never landed and the target
                // path is free; if we had moved the original aside, put it back.
                var restored = !backedUp
                if (backedUp) {
                    try {
                        check(gatewaySwitch.move(backupPath, target)) { "Restore move returned false" }
                        restored = true
                    } catch (restoreError: Exception) {
                        log(tag, ERROR) { "Restore failed - original preserved at $backupPath: ${restoreError.asLog()}" }
                        e.addSuppressed(restoreError)
                    }
                }
                cleanupArtifact(tempPath)
                if (!restored) {
                    throw CommitIntegrityException("Commit failed and the original could not be restored to $target", e)
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
    internal suspend fun replaceInPlace(
        target: APath<*>,
        backupPath: APath<*>,
        originalAccess: OriginalAccess,
        writer: suspend (EditorDataSource.CommitContext) -> Unit,
    ) {
        val targetExisted = target.exists(gatewaySwitch)
        if (originalAccess == OriginalAccess.FromTarget) {
            check(targetExisted) { "In-place splice requires an existing target: $target" }
        }
        var backupReady = false
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
                        throw CommitIntegrityException("In-place commit failed and $target could not be restored", e)
                    }
                }
                // Fresh target: partial writes are junk, nothing existed to restore
                !targetExisted -> {
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
    ): EditorDataSource.CommitContext = when (originalAccess) {
        OriginalAccess.FromTarget -> GatewayCommitContext(sink, readPath)
        OriginalAccess.None -> object : EditorDataSource.CommitContext {
            override val sink: BufferedSink = sink
            override suspend fun openOriginalSource(offset: Long): Source =
                throw IllegalStateException("Writer declared OriginalAccess.None but read the original")
        }
    }

    private inner class GatewayCommitContext(
        override val sink: BufferedSink,
        private val readPath: APath<*>,
    ) : EditorDataSource.CommitContext {
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

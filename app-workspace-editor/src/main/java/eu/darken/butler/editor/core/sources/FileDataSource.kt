package eu.darken.butler.editor.core.sources

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.LineEnding
import eu.darken.butler.editor.core.engine.text.CharsetDetector
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.use
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.Charset
import kotlin.uuid.Uuid

/**
 * File-based data source implementation.
 */
class FileDataSource @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val filePath: APath<*>,
    @Assisted private val gatewaySwitch: GatewaySwitch,
    @Assisted private val charsetOverride: Charset? = null,
) : EditorDataSource {
    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "DataSource", "File")
    private val _contentSource = MutableStateFlow<ContentSource>(ContentSource.Memory(size = 0L))
    override val contentSource: StateFlow<ContentSource> = _contentSource.asStateFlow()

    private val accessMutex = Mutex()

    /**
     * Reads the first 8KB for charset detection (enough for BOM + content validation).
     * Loops until the sample is full or EOF and trims to the bytes actually read - a single
     * read() may return short, and a zero-filled tail would skew detection.
     */
    private suspend fun readDetectionSample(): ByteArray {
        val sampleBytes = ByteArray(DETECTION_SAMPLE_SIZE)
        var filled = 0
        gatewaySwitch.file(filePath, readWrite = false).use { handle ->
            handle.source().buffer().use { source ->
                while (filled < sampleBytes.size) {
                    val read = source.read(sampleBytes, filled, sampleBytes.size - filled)
                    if (read == -1) break
                    filled += read
                }
            }
        }
        return if (filled == sampleBytes.size) sampleBytes else sampleBytes.copyOf(filled)
    }

    /**
     * Detects charset from file content via [CharsetDetector]:
     * BOM first, UTF-8 validation second, defaulting to UTF-8.
     *
     * @return Triple of (Charset, hasBOM, bomBytes)
     */
    private suspend fun detectCharset(): Triple<Charset, Boolean, ByteArray?> {
        val sampleBytes = readDetectionSample()
        if (sampleBytes.isEmpty()) {
            // Empty file - default to UTF-8
            return Triple(Charsets.UTF_8, false, null)
        }

        CharsetDetector.detectBom(sampleBytes)?.let { detection ->
            log(tag, INFO) { "Detected ${detection.charset} via BOM" }
            return Triple(detection.charset, true, detection.bomBytes)
        }

        if (CharsetDetector.isValidUtf8(sampleBytes)) {
            log(tag, INFO) { "Detected UTF-8 via validation (no BOM)" }
            return Triple(Charsets.UTF_8, false, null)
        }

        // Legacy encodings (ISO-8859-1, Windows-1252, Shift-JIS) will display as mojibake
        // but won't crash; the user can reopen with an explicit charset override.
        log(tag, WARN) { "Could not confidently detect encoding - defaulting to UTF-8" }
        return Triple(Charsets.UTF_8, false, null)
    }

    /**
     * Applies an explicit charset override, skipping detection. A BOM on disk is stripped
     * (and preserved on save) only when it belongs to the override's own family; any other
     * BOM bytes are treated as document content.
     */
    private suspend fun applyOverride(override: Charset): Triple<Charset, Boolean, ByteArray?> {
        val sampleBytes = readDetectionSample()
        val bom = if (sampleBytes.isEmpty()) null else CharsetDetector.detectBom(sampleBytes)
        log(tag, INFO) { "Using charset override $override (bom=${bom?.charset})" }
        return if (bom != null && bom.charset == override) {
            Triple(override, true, bom.bomBytes)
        } else {
            Triple(override, false, null)
        }
    }

    override suspend fun open() {
        log(tag) { "Opening file data source: $filePath" }
        try {
            if (!filePath.exists(gatewaySwitch)) {
                throw FileNotFoundException("File does not exist: $filePath")
            }

            val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)

            val (detectedCharset, hasBOM, bomBytes) = charsetOverride?.let { applyOverride(it) }
                ?: detectCharset()
            val canWrite = gatewaySwitch.canWrite(filePath)

            // A document without a reported size can't be block-indexed or staleness-checked;
            // a missing mtime is fine (staleness skips the mtime comparison when null)
            val size = lookup.size
                ?: throw IOException("Provider reported no size for $filePath")
            _contentSource.value = ContentSource.File(
                path = filePath,
                size = size,
                lastModified = lookup.modifiedAt,
                canWrite = canWrite,
                lineEnding = LineEnding.LF, // Updated by the engine after the block scan
                detectedCharset = detectedCharset,
                hasBOM = hasBOM,
                bomBytes = bomBytes
            )

            log(tag, INFO) {
                "Opened FileDataSource: size=${lookup.size} bytes, charset=$detectedCharset, " +
                    "hasBOM=$hasBOM, canWrite=$canWrite"
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to open - ${e.asLog()}" }
            throw e
        }
    }

    override suspend fun getSize(): Long = _contentSource.value.size

    override suspend fun getMeta(): EditorDataSource.Meta = withContext(Dispatchers.IO) {
        val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)
        // Coercing a missing size to 0 would fake a size-change staleness failure
        EditorDataSource.Meta(
            size = lookup.size ?: throw IOException("Provider reported no size for $filePath"),
            modifiedAt = lookup.modifiedAt,
        )
    }

    override suspend fun openByteSource(offset: Long): Source = withContext(Dispatchers.IO) {
        val handle = gatewaySwitch.file(filePath, readWrite = false)
        val source = handle.source(fileOffset = offset)
        object : Source by source {
            override fun close() {
                source.close()
                handle.close()
            }
        }
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

    override suspend fun commit(writer: suspend (EditorDataSource.CommitContext) -> Unit) = accessMutex.withLock {
        withContext(Dispatchers.IO) {
            val fileSource = _contentSource.value as? ContentSource.File
                ?: error("ContentSource.File not initialized")

            // Unique per-save artifact names so we never collide with or delete a user's own
            // files, and never touch artifacts from a different (e.g. crashed) save.
            val token = Uuid.random().toString().take(8)
            val parent = filePath.parent
                ?: throw IllegalStateException("Cannot save - no parent directory")
            val backupPath = parent.child("${filePath.name}.butler-save-bak-$token")

            if (filePath is LocalPath) {
                val tempPath = parent.child("${filePath.name}.butler-save-tmp-$token")
                commitViaBackupSwap(tempPath, backupPath, writer)
            } else {
                commitViaInPlace(backupPath, writer)
            }

            // The commit has landed; a metadata refresh failure must not be reported as a
            // failed commit (consumers re-read metadata during their post-save rescan anyway)
            runCatching {
                val lookup = filePath.lookup(gatewaySwitch, LookupOptions.BASE)
                _contentSource.value = fileSource.copy(
                    size = lookup.size ?: fileSource.size,
                    lastModified = lookup.modifiedAt,
                )
            }.onFailure { log(tag, WARN) { "Post-commit metadata refresh failed: ${it.asLog()}" } }
            log(tag) { "Commit landed for $filePath" }
        }
    }

    /**
     * Local-path streaming commit: the writer streams into a uniquely-named temp while the original
     * stays untouched and readable (cancellation-safe); the rename swap is the point of no return
     * and runs non-cancellable, restoring the original on failure.
     */
    internal suspend fun commitViaBackupSwap(
        tempPath: APath<*>,
        backupPath: APath<*>,
        writer: suspend (EditorDataSource.CommitContext) -> Unit,
    ) {
        try {
            writeContent(tempPath) { sink ->
                writer(GatewayCommitContext(sink, readPath = filePath))
            }
        } catch (e: Exception) {
            cleanupArtifact(tempPath)
            throw e
        }

        withContext(NonCancellable) {
            var backedUp = false
            try {
                if (filePath.exists(gatewaySwitch)) {
                    check(gatewaySwitch.move(filePath, backupPath)) { "Backup move failed: $filePath -> $backupPath" }
                    backedUp = true
                }
                check(gatewaySwitch.move(tempPath, filePath)) { "Commit move failed: $tempPath -> $filePath" }
            } catch (e: Exception) {
                // Moves are atomic, so a failure here means the commit never landed and the original
                // path is free; if we had moved the original aside, put it back.
                var restored = !backedUp
                if (backedUp) {
                    try {
                        check(gatewaySwitch.move(backupPath, filePath)) { "Restore move returned false" }
                        restored = true
                    } catch (restoreError: Exception) {
                        log(tag, ERROR) { "Restore failed - original preserved at $backupPath: ${restoreError.asLog()}" }
                        e.addSuppressed(restoreError)
                    }
                }
                cleanupArtifact(tempPath)
                if (!restored) {
                    throw CommitIntegrityException("Commit failed and the original could not be restored to $filePath", e)
                }
                throw e
            }
            cleanupArtifact(backupPath)
        }
    }

    /**
     * SAF/non-local streaming commit: the original is copied to a uniquely-named backup first
     * (cancellation-safe), then the document is overwritten in place with the writer reading
     * original ranges FROM THE BACKUP; the overwrite runs non-cancellable and the original is
     * restored from the backup on failure (backup retained if the restore also fails).
     */
    internal suspend fun commitViaInPlace(
        backupPath: APath<*>,
        writer: suspend (EditorDataSource.CommitContext) -> Unit,
    ) {
        var backupReady = false
        try {
            gatewaySwitch.createFile(backupPath, createParents = false)
            writeContent(backupPath) { sink ->
                gatewaySwitch.file(filePath, readWrite = false).use { handle ->
                    handle.source().buffer().use { source -> sink.writeAll(source) }
                }
            }
            backupReady = true

            withContext(NonCancellable) {
                writeContent(filePath) { sink ->
                    writer(GatewayCommitContext(sink, readPath = backupPath))
                }
            }
        } catch (e: Exception) {
            if (backupReady) {
                var restored = false
                withContext(NonCancellable) {
                    runCatching {
                        writeContent(filePath) { sink ->
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
                    throw CommitIntegrityException("In-place commit failed and $filePath could not be restored", e)
                }
            } else {
                // Backup never completed; the original was not touched, so the partial backup is junk.
                cleanupArtifact(backupPath)
            }
            throw e
        }

        cleanupArtifact(backupPath)
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

    override suspend fun close() = accessMutex.withLock {
        _contentSource.value = ContentSource.Memory(size = 0L)

        log(tag) { "Closed FileDataSource and released resources" }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            filePath: APath<*>,
            gatewaySwitch: GatewaySwitch,
            charsetOverride: Charset? = null,
        ): FileDataSource
    }

    companion object {
        const val DETECTION_SAMPLE_SIZE = 8192
    }
}

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
    private val atomicFileWriter = AtomicFileWriter(gatewaySwitch, tag)

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

            atomicFileWriter.replace(filePath, AtomicFileWriter.OriginalAccess.FromTarget, writer)

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

    internal suspend fun commitViaBackupSwap(
        tempPath: APath<*>,
        backupPath: APath<*>,
        writer: suspend (EditorDataSource.CommitContext) -> Unit,
    ) = atomicFileWriter.replaceViaTempSwap(
        target = filePath,
        tempPath = tempPath,
        backupPath = backupPath,
        originalAccess = AtomicFileWriter.OriginalAccess.FromTarget,
        writer = writer,
    )

    internal suspend fun commitViaInPlace(
        backupPath: APath<*>,
        writer: suspend (EditorDataSource.CommitContext) -> Unit,
    ) = atomicFileWriter.replaceInPlace(
        target = filePath,
        backupPath = backupPath,
        originalAccess = AtomicFileWriter.OriginalAccess.FromTarget,
        writer = writer,
    )

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

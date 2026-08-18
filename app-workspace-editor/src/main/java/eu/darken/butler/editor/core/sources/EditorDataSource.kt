package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.files.write.FileCommitContext
import eu.darken.butler.editor.core.engine.ContentSource
import kotlinx.coroutines.flow.StateFlow
import okio.Source
import java.io.FileNotFoundException
import kotlin.time.Instant

/**
 * Byte-level data source for editor content, backing the piece-table engine.
 *
 * This is a pure I/O layer: it detects the charset/BOM on [open] (exposed via
 * [ContentSource.File]), serves positional byte reads, and atomically replaces the full
 * content on [commit]. Text semantics (decoding into blocks, line endings, offsets,
 * modification tracking) live in the engine, not here.
 */
interface EditorDataSource {
    val contentSource: StateFlow<ContentSource>

    /**
     * Opens the data source and prepares it for reading/writing.
     * @throws IOException if the resource cannot be opened
     * @throws FileNotFoundException if the resource doesn't exist
     */
    suspend fun open()

    suspend fun getSize(): Long

    suspend fun close()

    /**
     * Physical metadata for staleness checks: size in bytes (including any BOM) and
     * last modification time (null when the backend has none, e.g. in-memory sources).
     */
    suspend fun getMeta(): Meta

    /**
     * Opens a byte source positioned at [offset] physical bytes (positional, not a
     * forward-only skip). Caller is responsible for closing the Source.
     */
    suspend fun openByteSource(offset: Long = 0L): Source

    /**
     * Atomically replaces the full content with whatever [writer] streams into the context's sink.
     *
     * Contract:
     * - [FileCommitContext.openOriginalSource] serves the PRE-COMMIT content (physical bytes, BOM
     *   included) for the whole duration of the commit, regardless of backend mode.
     * - The sink is flushed and closed by commit; the writer never closes it.
     * - If the writer throws, the original content is restored and no partial target remains.
     * - Cancellation is honored while writing to temp/backup artifacts; the final target
     *   replacement runs non-cancellable.
     */
    suspend fun commit(writer: suspend (FileCommitContext) -> Unit)

    data class Meta(
        val size: Long,
        val modifiedAt: Instant?,
    )
}

package eu.darken.butler.editor.core.engine

/**
 * Tracks the offset boundaries of a chunk.
 * Chunk IDs are sequential/opaque, boundaries track actual file positions.
 */
data class ChunkBoundary(
    val startOffset: Long,
    val endOffset: Long,
    val lineCount: Int
) {
    val size: Long get() = endOffset - startOffset
}
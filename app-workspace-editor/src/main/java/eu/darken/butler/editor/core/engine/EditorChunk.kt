package eu.darken.butler.editor.core.engine

import kotlin.uuid.Uuid

/**
 * Sealed class representing a chunk of editor data.
 * Can be either text (String) or binary (ByteArray).
 *
 * This replaces the old TextChunk class with a more flexible architecture
 * that supports both text and binary editing modes.
 */
sealed class EditorChunk {
    abstract val offset: Long
    abstract val size: Long
    abstract val isDirty: Boolean
    abstract val refCount: Int
    abstract val isPinned: Boolean

    /**
     * Mark this chunk as dirty (modified).
     */
    abstract fun markDirty(): EditorChunk

    /**
     * Text chunk containing UTF-8 string content with line metadata.
     * Used for text editing mode.
     */
    data class Text(
        override val offset: Long,
        val content: String,
        override val size: Long,
        val lineCount: Int,
        val lineEnding: LineEnding,
        override val isDirty: Boolean,
        val isLoaded: Boolean = true,
        override val refCount: Int = 0,
        val id: ChunkId = ChunkId.generate()
    ) : EditorChunk() {
        override val isPinned: Boolean get() = refCount > 0 || isDirty

        override fun markDirty(): EditorChunk = copy(isDirty = true)

        fun markClean(): EditorChunk = copy(isDirty = false)

        fun pin(): EditorChunk = copy(refCount = refCount + 1)

        fun unpin(): EditorChunk {
            require(refCount > 0) { "Cannot unpin chunk $id with refCount=$refCount" }
            return copy(refCount = refCount - 1)
        }

        val isEmpty: Boolean get() = content.isEmpty()
    }

    /**
     * Binary chunk containing raw bytes.
     * Used for hex editing mode.
     */
    data class Binary(
        override val offset: Long,
        val content: ByteArray,
        override val size: Long,
        override val isDirty: Boolean,
        override val refCount: Int = 0,
        val id: ChunkId = ChunkId.generate()
    ) : EditorChunk() {
        override val isPinned: Boolean get() = refCount > 0 || isDirty

        override fun markDirty(): EditorChunk = copy(isDirty = true)

        fun markClean(): EditorChunk = copy(isDirty = false)

        fun pin(): EditorChunk = copy(refCount = refCount + 1)

        fun unpin(): EditorChunk {
            require(refCount > 0) { "Cannot unpin chunk $id with refCount=$refCount" }
            return copy(refCount = refCount - 1)
        }

        /**
         * Binary chunks are always loaded when in memory.
         */
        val isLoaded: Boolean = true

        /**
         * Custom equals for ByteArray content comparison.
         * Data classes don't compare ByteArray contents by default.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false

            if (offset != other.offset) return false
            if (!content.contentEquals(other.content)) return false
            if (size != other.size) return false
            if (isDirty != other.isDirty) return false
            if (refCount != other.refCount) return false
            if (isPinned != other.isPinned) return false
            if (id != other.id) return false

            return true
        }

        /**
         * Custom hashCode for ByteArray content.
         */
        override fun hashCode(): Int {
            var result = offset.hashCode()
            result = 31 * result + content.contentHashCode()
            result = 31 * result + size.hashCode()
            result = 31 * result + isDirty.hashCode()
            result = 31 * result + refCount.hashCode()
            result = 31 * result + isPinned.hashCode()
            result = 31 * result + id.hashCode()
            return result
        }
    }

    /**
     * Unique identifier for a chunk.
     * Compatible with TextChunk.ChunkId for migration.
     */
    @JvmInline
    value class ChunkId(val value: String) {
        companion object {
            private val counter = java.util.concurrent.atomic.AtomicInteger(0)

            fun generate(): ChunkId = ChunkId("chunk_${counter.getAndIncrement()}")

            fun resetCounter() {
                counter.set(0)
            }
        }

        val shortTag: String
            get() = value
    }
}

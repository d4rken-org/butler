package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import java.nio.charset.Charset
import kotlin.time.Instant

/**
 * Represents the source of editor content.
 * Can be either a file on disk or an in-memory buffer.
 */
sealed class ContentSource {
    abstract val name: String
    abstract val size: Long

    /**
     * Whether this source has content worth preserving.
     * Used to determine if Close button should be visible.
     */
    val hasContent: Boolean get() = size > 0

    /**
     * In-memory buffer with no backing file.
     */
    data class Memory(
        override val size: Long = 0L,
        val suggestedName: String? = null,
    ) : ContentSource() {
        override val name: String get() = suggestedName ?: "Untitled"
    }

    /**
     * File-backed content with metadata.
     */
    data class File(
        val path: APath<*>,
        override val size: Long,
        val lastModified: Instant?,
        val canWrite: Boolean,
        val lineEnding: LineEnding = LineEnding.LF,
        val detectedCharset: Charset = Charsets.UTF_8,
        val hasBOM: Boolean = false,
        val bomBytes: ByteArray? = null,
        /** Leftover backup artifacts from interrupted saves, found next to the file at open time. */
        val staleBackups: List<APath<*>> = emptyList(),
    ) : ContentSource() {
        override val name: String get() = path.name

        // Manual equals/hashCode for the ByteArray field. Every property MUST be included -
        // a missed field makes StateFlow/Compose dedup its updates invisibly.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as File

            if (path != other.path) return false
            if (size != other.size) return false
            if (lastModified != other.lastModified) return false
            if (canWrite != other.canWrite) return false
            if (lineEnding != other.lineEnding) return false
            if (detectedCharset != other.detectedCharset) return false
            if (hasBOM != other.hasBOM) return false
            if (bomBytes != null) {
                if (other.bomBytes == null) return false
                if (!bomBytes.contentEquals(other.bomBytes)) return false
            } else if (other.bomBytes != null) return false
            if (staleBackups != other.staleBackups) return false

            return true
        }

        override fun hashCode(): Int {
            var result = path.hashCode()
            result = 31 * result + size.hashCode()
            result = 31 * result + (lastModified?.hashCode() ?: 0)
            result = 31 * result + canWrite.hashCode()
            result = 31 * result + lineEnding.hashCode()
            result = 31 * result + detectedCharset.hashCode()
            result = 31 * result + hasBOM.hashCode()
            result = 31 * result + (bomBytes?.contentHashCode() ?: 0)
            result = 31 * result + staleBackups.hashCode()
            return result
        }
    }
}

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
        /** Null bytes in the detection sample (post-BOM, non-UTF-16): treated as read-only. */
        val isLikelyBinary: Boolean = false,
        /**
         * True when the file (as of open/rebase) has at least one line longer than the display
         * cap. Deliberately stale in-session: lines grown past the cap by edits update this only
         * after the next save's rebase - the display cap itself is applied unconditionally.
         */
        val hasLongLines: Boolean = false,
        /**
         * Set once the backing file becomes unreadable mid-session (deleted or read permission
         * lost). Latches: the piece table can no longer read original bytes, so the document goes
         * read-only and edits/saves are refused. Cleared only by a successful reopen/reload.
         */
        val isBackingLost: Boolean = false,
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
            if (isLikelyBinary != other.isLikelyBinary) return false
            if (hasLongLines != other.hasLongLines) return false
            if (isBackingLost != other.isBackingLost) return false

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
            result = 31 * result + isLikelyBinary.hashCode()
            result = 31 * result + hasLongLines.hashCode()
            result = 31 * result + isBackingLost.hashCode()
            return result
        }
    }
}

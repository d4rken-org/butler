package eu.darken.butler.editor.core.engine

/**
 * Binary edit operations for undo/redo in binary buffers.
 */
sealed interface BinaryEditOperation {
    val offset: Long
    val timestamp: Long

    data class Insert(
        override val offset: Long,
        val data: ByteArray,
        override val timestamp: Long = System.currentTimeMillis()
    ) : BinaryEditOperation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Insert) return false
            if (offset != other.offset) return false
            if (!data.contentEquals(other.data)) return false
            if (timestamp != other.timestamp) return false
            return true
        }

        override fun hashCode(): Int {
            var result = offset.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }

    data class Delete(
        override val offset: Long,
        val length: Long,
        val deletedData: ByteArray,
        override val timestamp: Long = System.currentTimeMillis()
    ) : BinaryEditOperation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Delete) return false
            if (offset != other.offset) return false
            if (length != other.length) return false
            if (!deletedData.contentEquals(other.deletedData)) return false
            if (timestamp != other.timestamp) return false
            return true
        }

        override fun hashCode(): Int {
            var result = offset.hashCode()
            result = 31 * result + length.hashCode()
            result = 31 * result + deletedData.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }

    data class Replace(
        override val offset: Long,
        val oldData: ByteArray,
        val newData: ByteArray,
        override val timestamp: Long = System.currentTimeMillis()
    ) : BinaryEditOperation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Replace) return false
            if (offset != other.offset) return false
            if (!oldData.contentEquals(other.oldData)) return false
            if (!newData.contentEquals(other.newData)) return false
            if (timestamp != other.timestamp) return false
            return true
        }

        override fun hashCode(): Int {
            var result = offset.hashCode()
            result = 31 * result + oldData.contentHashCode()
            result = 31 * result + newData.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
}

/**
 * Estimates the memory footprint of a BinaryEditOperation in bytes.
 */
fun BinaryEditOperation.estimateMemoryBytes(): Long {
    val baseSize = 16L  // offset (8 bytes) + timestamp (8 bytes)
    return when (this) {
        is BinaryEditOperation.Insert -> baseSize + data.size
        is BinaryEditOperation.Delete -> baseSize + 8L + deletedData.size  // +8 for length Long
        is BinaryEditOperation.Replace -> baseSize + oldData.size + newData.size
    }
}

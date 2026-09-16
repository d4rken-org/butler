package eu.darken.butler.common.files.mediastore

import android.database.Cursor
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import java.io.File
import kotlin.time.Instant

/**
 * Raw MediaStore row, read from a cursor with NULL columns preserved
 * (Cursor.getLong turns SQL NULL into 0, which must not become size=0 or a 1970 timestamp).
 */
data class MediaStoreRow(
    val data: String?,
    val size: Long?,
    val modifiedAtEpochSeconds: Long?,
    val addedAtEpochSeconds: Long? = null,
)

/** A null [addedIndex] leaves [MediaStoreRow.addedAtEpochSeconds] null, for readers without that column. */
fun Cursor.readMediaStoreRow(
    dataIndex: Int,
    sizeIndex: Int,
    modifiedIndex: Int,
    addedIndex: Int? = null,
) = MediaStoreRow(
    data = if (isNull(dataIndex)) null else getString(dataIndex),
    size = if (isNull(sizeIndex)) null else getLong(sizeIndex),
    modifiedAtEpochSeconds = if (isNull(modifiedIndex)) null else getLong(modifiedIndex),
    addedAtEpochSeconds = addedIndex?.let { if (isNull(it)) null else getLong(it) },
)

/**
 * Pure conversion of a [MediaStoreRow] into a [LocalPathLookup]. Only validation/conversion
 * logic — no Android dependencies, directly unit-testable.
 */
object MediaStoreRowDecoder {

    sealed interface Outcome {
        /** [addedAt] is the index time, null when the provider left it unset or at a sentinel value. */
        data class Decoded(
            val lookup: LocalPathLookup,
            val addedAt: Instant? = null,
        ) : Outcome

        /** Row has no usable filesystem path (NULL/blank DATA, e.g. redacted on scoped storage). */
        data object Unrepresentable : Outcome

        /** Row is malformed (e.g. relative DATA path) — counts toward partial results. */
        data class Invalid(val reason: String) : Outcome
    }

    fun decode(row: MediaStoreRow): Outcome {
        val data = row.data
        if (data.isNullOrBlank()) return Outcome.Unrepresentable

        val file = File(data)
        if (!file.isAbsolute) return Outcome.Invalid("DATA is not absolute: $data")

        return Outcome.Decoded(
            lookup = LocalPathLookup(
                lookedUp = LocalPath.build(file),
                // MediaStore collections only index files, never directories
                fileType = FileType.FILE,
                size = row.size?.takeIf { it >= 0 },
                modifiedAt = row.modifiedAtEpochSeconds
                    ?.takeIf { it > 0 }
                    ?.let { Instant.fromEpochSeconds(it) },
                // DATE_ADDED is index-time, not file creation time — createdAt stays null
                createdAt = null,
            ),
            addedAt = row.addedAtEpochSeconds
                ?.takeIf { it > 0 }
                ?.let { Instant.fromEpochSeconds(it) },
        )
    }
}

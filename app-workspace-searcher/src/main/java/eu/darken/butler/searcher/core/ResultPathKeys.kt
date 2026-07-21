package eu.darken.butler.searcher.core

import android.annotation.SuppressLint
import android.os.Environment
import androidx.annotation.VisibleForTesting
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import java.io.File

/**
 * Syntactic normalization for result identity: the same file can surface under different
 * spellings — `/sdcard/x` from a user-picked target vs `/storage/emulated/<user>/x` from
 * MediaStore. Dedup, replacement, and tombstone pruning compare through this normalization so
 * alias spellings meet. Purely syntactic (no filesystem I/O): known primary-storage aliases for
 * the CURRENT Android user plus `.`/`..` segment resolution. Physical identity across gateway
 * types (SAF vs local views of the same file) is intentionally NOT unified.
 */
// SdCardPath: the alias literals ARE the point — they are compared against, never accessed
@SuppressLint("SdCardPath")
internal object ResultPathKeys {

    /**
     * The current user's primary storage root, e.g. `/storage/emulated/10` for user 10 —
     * hardcoding user 0 would break dedup/pruning in work profiles. Falls back to user 0 in
     * plain JVM unit tests where [Environment] is stubbed.
     */
    @VisibleForTesting
    internal var primaryStorage: String = runCatching { Environment.getExternalStorageDirectory().absolutePath }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "/storage/emulated/0"

    private val primaryAliases: List<String>
        get() {
            val userId = primaryStorage.substringAfterLast('/')
            return listOf(
                "/sdcard",
                "/storage/self/primary",
                "/mnt/sdcard",
                "/mnt/user/$userId/primary",
            )
        }

    fun keyOf(path: APath<*>): String = when (path) {
        is LocalPath -> normalize(path.path)
        else -> path.path
    }

    /** Normalized copy for ancestry comparisons; non-local paths are returned as-is. */
    fun comparable(path: APath<*>): APath<*> = when (path) {
        is LocalPath -> {
            val normalized = normalize(path.path)
            if (normalized == path.path) path else LocalPath.build(File(normalized))
        }
        else -> path
    }

    private fun normalize(raw: String): String {
        var result = File(raw).normalize().path
        for (alias in primaryAliases) {
            if (result == alias) return primaryStorage
            if (result.startsWith("$alias/")) {
                result = primaryStorage + result.substring(alias.length)
                break
            }
        }
        return result
    }
}

/**
 * Stable identity of a result across source spellings: selection, quick-action rebinding, and
 * lazy-list keys must use this — a rank replacement can swap an item for an alias-spelled twin,
 * and raw `path.path` identity would drop the selection or close the sheet.
 */
internal val SearchItem.resultKey: String
    get() = ResultPathKeys.keyOf(path)

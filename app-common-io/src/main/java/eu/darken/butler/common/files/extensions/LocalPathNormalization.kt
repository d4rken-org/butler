package eu.darken.butler.common.files.extensions

import android.annotation.SuppressLint
import android.os.Environment
import androidx.annotation.VisibleForTesting
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import java.io.File

/**
 * Syntactic normalization for local paths: the same file can surface under different spellings —
 * `/sdcard/x` from a user-picked target vs `/storage/emulated/<user>/x` from MediaStore. Anything
 * comparing paths for identity or ancestry has to compare through this, otherwise alias spellings
 * never meet.
 *
 * Purely syntactic (no filesystem I/O): known primary-storage aliases for the CURRENT Android user
 * plus `.`/`..` segment resolution. Physical identity across gateway types (SAF vs local views of
 * the same file) is intentionally NOT unified.
 */
// SdCardPath: the alias literals ARE the point — they are compared against, never accessed
@SuppressLint("SdCardPath")
object LocalPathNormalization {

    /**
     * The current user's primary storage root, e.g. `/storage/emulated/10` for user 10 —
     * hardcoding user 0 would break comparisons in work profiles. Falls back to user 0 in plain
     * JVM unit tests where [Environment] is stubbed.
     */
    @VisibleForTesting
    var primaryStorage: String = runCatching { Environment.getExternalStorageDirectory().absolutePath }
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

    /** Normalized string form; non-local paths are returned as-is. */
    fun keyOf(path: APath<*>): String = when (path) {
        is LocalPath -> normalize(path.path)
        else -> path.path
    }

    /** Normalized copy for identity and ancestry comparisons; non-local paths are returned as-is. */
    fun comparable(path: APath<*>): APath<*> = when (path) {
        is LocalPath -> {
            val normalized = normalize(path.path)
            if (normalized == path.path) path else LocalPath.build(File(normalized))
        }
        else -> path
    }

    fun normalize(raw: String): String {
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

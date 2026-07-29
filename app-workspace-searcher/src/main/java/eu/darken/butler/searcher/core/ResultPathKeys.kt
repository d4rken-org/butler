package eu.darken.butler.searcher.core

import androidx.annotation.VisibleForTesting
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.extensions.LocalPathNormalization

/**
 * Result identity, built on the shared [LocalPathNormalization]: the same file can surface under
 * different spellings — `/sdcard/x` from a user-picked target vs `/storage/emulated/<user>/x` from
 * MediaStore. Dedup, replacement, and tombstone pruning compare through this normalization so
 * alias spellings meet.
 */
internal object ResultPathKeys {

    /** @see LocalPathNormalization.primaryStorage */
    @VisibleForTesting
    internal var primaryStorage: String
        get() = LocalPathNormalization.primaryStorage
        set(value) {
            LocalPathNormalization.primaryStorage = value
        }

    fun keyOf(path: APath<*>): String = LocalPathNormalization.keyOf(path)

    /** Normalized copy for ancestry comparisons; non-local paths are returned as-is. */
    fun comparable(path: APath<*>): APath<*> = LocalPathNormalization.comparable(path)
}

/**
 * Stable identity of a result across source spellings: selection, quick-action rebinding, and
 * lazy-list keys must use this — a rank replacement can swap an item for an alias-spelled twin,
 * and raw `path.path` identity would drop the selection or close the sheet.
 */
internal val SearchItem.resultKey: String
    get() = ResultPathKeys.keyOf(path)

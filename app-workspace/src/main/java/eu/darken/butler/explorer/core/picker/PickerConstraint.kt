package eu.darken.butler.explorer.core.picker

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Composable constraints for determining picker item selectability and visibility.
 * Evaluated against ExplorerItem in the explorer module via [matches] extension function.
 *
 * Constraints can be combined using logical operators:
 * - [And]: All constraints must match
 * - [Or]: At least one constraint must match
 * - [Not]: Inverts a constraint
 *
 * Example: `anyOf(IsDirectory, IsStorage)` or `allOf(IsFile, MaxSize(5_000_000))`
 */
sealed interface PickerConstraint : Parcelable {

    // ═══════════════════════════════════════════════════════════════
    // Type Constraints
    // ═══════════════════════════════════════════════════════════════

    /** Matches [ExplorerItem.Directory] */
    @Parcelize
    data object IsDirectory : PickerConstraint

    /** Matches [ExplorerItem.File] */
    @Parcelize
    data object IsFile : PickerConstraint

    /** Matches [ExplorerItem.Storage] */
    @Parcelize
    data object IsStorage : PickerConstraint

    /** Matches [ExplorerItem.Shortcut] */
    @Parcelize
    data object IsShortcut : PickerConstraint

    // ═══════════════════════════════════════════════════════════════
    // Property Constraints
    // ═══════════════════════════════════════════════════════════════

    /** Matches directories with childCount == 0 */
    @Parcelize
    data object IsEmpty : PickerConstraint

    /** Matches files with MIME type matching [pattern]. Supports wildcards like "image/star". */
    @Parcelize
    data class HasMimeType(val pattern: String) : PickerConstraint

    /** Matches items with size <= [bytes] */
    @Parcelize
    data class MaxSize(val bytes: Long) : PickerConstraint

    /** Matches items with size >= [bytes] */
    @Parcelize
    data class MinSize(val bytes: Long) : PickerConstraint

    /** Matches shortcuts with specific [id] */
    @Parcelize
    data class HasShortcutId(val id: String) : PickerConstraint

    /** Matches items where canWrite != false (writable or unknown). */
    @Parcelize
    data object IsWritable : PickerConstraint

    // ═══════════════════════════════════════════════════════════════
    // Logical Operators
    // ═══════════════════════════════════════════════════════════════

    /** All [constraints] must match */
    @Parcelize
    data class And(val constraints: List<PickerConstraint>) : PickerConstraint

    /** At least one of [constraints] must match */
    @Parcelize
    data class Or(val constraints: List<PickerConstraint>) : PickerConstraint

    /** Inverts the [constraint] */
    @Parcelize
    data class Not(val constraint: PickerConstraint) : PickerConstraint

    // ═══════════════════════════════════════════════════════════════
    // Terminal Constraints
    // ═══════════════════════════════════════════════════════════════

    /** Always matches */
    @Parcelize
    data object Any : PickerConstraint

    /** Never matches */
    @Parcelize
    data object None : PickerConstraint
}

// ═══════════════════════════════════════════════════════════════════════
// DSL Builder Functions
// ═══════════════════════════════════════════════════════════════════════

/** Any of the constraints must match (OR). Returns [PickerConstraint.None] if empty. */
fun anyOf(vararg constraints: PickerConstraint): PickerConstraint = when (constraints.size) {
    0 -> PickerConstraint.None
    1 -> constraints.first()
    else -> PickerConstraint.Or(constraints.toList())
}

/** All constraints must match (AND). Returns [PickerConstraint.Any] if empty. */
fun allOf(vararg constraints: PickerConstraint): PickerConstraint = when (constraints.size) {
    0 -> PickerConstraint.Any
    1 -> constraints.first()
    else -> PickerConstraint.And(constraints.toList())
}

/** Invert the constraint (NOT) */
fun not(constraint: PickerConstraint): PickerConstraint = PickerConstraint.Not(constraint)

package eu.darken.butler.workspace.contracts.explorer

import eu.darken.butler.explorer.core.engine.ExplorerItem

/**
 * Evaluates [PickerConstraint] against [ExplorerItem].
 * This bridges the workspace module (constraints) with explorer module (items).
 */
fun PickerConstraint.matches(item: ExplorerItem): Boolean = when (this) {
    // Type constraints
    is PickerConstraint.IsDirectory -> item is ExplorerItem.Directory
    is PickerConstraint.IsFile -> item is ExplorerItem.File
    is PickerConstraint.IsStorage -> item is ExplorerItem.Storage
    is PickerConstraint.IsShortcut -> item is ExplorerItem.Shortcut

    // Property constraints
    is PickerConstraint.IsEmpty -> {
        (item as? ExplorerItem.Directory)?.childCount == 0
    }

    is PickerConstraint.HasMimeType -> {
        val file = item as? ExplorerItem.File ?: return false
        val regex = Regex(pattern.replace("*", ".*"))
        file.mimeType.rawType.matches(regex)
    }

    is PickerConstraint.MaxSize -> {
        val lookup = item as? ExplorerItem.Lookup ?: return true
        lookup.lookup.size?.let { it <= bytes } ?: true
    }

    is PickerConstraint.MinSize -> {
        val lookup = item as? ExplorerItem.Lookup ?: return false
        lookup.lookup.size?.let { it >= bytes } ?: false
    }

    is PickerConstraint.HasShortcutId -> {
        (item as? ExplorerItem.Shortcut)?.shortcutId == id
    }

    is PickerConstraint.IsWritable -> {
        when (item) {
            is ExplorerItem.Lookup -> item.canWrite != false
            is ExplorerItem.Storage -> item.canWrite != false
            else -> true
        }
    }

    // Logical operators
    is PickerConstraint.And -> constraints.all { it.matches(item) }
    is PickerConstraint.Or -> constraints.any { it.matches(item) }
    is PickerConstraint.Not -> !constraint.matches(item)

    // Terminals
    is PickerConstraint.Any -> true
    is PickerConstraint.None -> false
}

/**
 * Returns true if [item] is a valid selection target for this selection mode.
 */
fun PickerConfig.Selection.isSelectable(item: ExplorerItem): Boolean =
    selectableConstraint.matches(item)

/**
 * Returns true if [item] should be visually disabled (greyed out) for this selection mode.
 */
fun PickerConfig.Selection.isDisabled(item: ExplorerItem): Boolean =
    disabledConstraint.matches(item)

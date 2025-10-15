package eu.darken.butler.workspace.core.picker

import android.os.Parcelable
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize

/**
 * Runtime picker configuration stored in workspace instance.
 * NOT flowed through Workspace.Info (immutable after creation).
 *
 * This configuration determines:
 * - What types of items can be selected (files, folders, or both)
 * - How the user interacts with items (tap to select, navigate, or toggle)
 * - Whether single or multiple items can be selected
 * - Visual presentation and button behavior
 */
data class PickerConfig(
    /**
     * Workspace ID that launched this picker
     */
    val callerWorkspaceId: Workspace.Id,

    /**
     * Defines what can be selected and how user interacts with items.
     *
     * Common configurations:
     * - [Selection.DirectorySingle]: Navigate folders, select current via button
     * - [Selection.FileSingle]: Tap file for instant selection
     * - [Selection.FileMulti]: Tap files to toggle selection
     * - [Selection.DirectoryMulti]: Long-press folders to toggle selection
     */
    val selection: Selection,
) {
    /**
     * Defines what the user can select and how they interact with items in the picker.
     */
    sealed class Selection : Parcelable {

        /**
         * Select a single directory by navigating to it and using the Select button.
         *
         * Interaction:
         * - Tap folder → Navigate into it
         * - Select button → Confirms current directory
         *
         * Selectability:
         * - Only real directories (not virtual paths like "Home" or "Device Storage")
         * - Select button always enabled if path is selectable
         *
         * Use case: Choose target directory for search, file operations, etc.
         */
        @Parcelize
        data object DirectorySingle : Selection()

        /**
         * Select multiple directories via long-press selection mode.
         *
         * Interaction:
         * - Tap folder → Navigate into it
         * - Long-press folder → Toggle selection
         * - Select button → Confirms selected directories
         *
         * Selectability:
         * - Only real directories (not virtual paths)
         * - Select button enabled when at least one directory selected
         *
         * Use case: Batch operations on multiple folders.
         */
        @Parcelize
        data object DirectoryMulti : Selection()

        /**
         * Select a single file by tapping it - instant selection with no confirmation needed.
         *
         * Interaction:
         * - Tap file → Instant selection + picker closes automatically
         * - Tap folder → Navigate into it
         * - No Select button needed (or disabled/hidden)
         *
         * Selectability:
         * - Only files (not directories)
         *
         * Use case: Open file, attach file to message, etc.
         */
        @Parcelize
        data object FileSingle : Selection()

        /**
         * Select multiple files via tap-to-toggle selection.
         *
         * Interaction:
         * - Tap file → Toggle selection (checkbox appears)
         * - Tap folder → Navigate into it
         * - Select button → Confirms selected files
         *
         * Selectability:
         * - Only files (not directories)
         * - Select button enabled when at least one file selected
         * - Shows selection count: "Done (3 files)"
         *
         * Use case: Attach multiple files, batch file operations.
         */
        @Parcelize
        data object FileMulti : Selection()

        // Future: MixedMulti for selecting both files and folders
        // Deferred due to complex UX (tap file = select, tap folder = navigate OR select?)

        /**
         * Returns true if this mode allows selecting multiple items.
         */
        val isMultiSelect: Boolean
            get() = when (this) {
                is DirectorySingle, is FileSingle -> false
                is DirectoryMulti, is FileMulti -> true
            }

        /**
         * Returns true if this mode selects directories.
         */
        val selectsDirectories: Boolean
            get() = when (this) {
                is DirectorySingle, is DirectoryMulti -> true
                is FileSingle, is FileMulti -> false
            }

        /**
         * Returns true if this mode selects files.
         */
        val selectsFiles: Boolean
            get() = when (this) {
                is DirectorySingle, is DirectoryMulti -> false
                is FileSingle, is FileMulti -> true
            }

        /**
         * Returns true if tapping a file should instantly select it (and close the picker).
         */
        val instantFileSelection: Boolean
            get() = this is FileSingle
    }
}

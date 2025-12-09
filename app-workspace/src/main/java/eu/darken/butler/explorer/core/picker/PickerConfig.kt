package eu.darken.butler.explorer.core.picker

import android.os.Parcelable
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.IgnoredOnParcel
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
     *
     * Each selection mode defines:
     * - [selectableConstraint]: What items are valid selection targets
     * - [disabledConstraint]: What items should be visually disabled (greyed out)
     */
    sealed class Selection : Parcelable {

        /** Constraint defining what items are valid selection targets */
        abstract val selectableConstraint: PickerConstraint

        /** Constraint defining what items should be visually disabled */
        abstract val disabledConstraint: PickerConstraint

        /**
         * Select a single directory by navigating to it and using the Select button.
         *
         * Interaction:
         * - Tap folder → Navigate into it
         * - Tap storage volume → Navigate into it
         * - Long-press storage volume (at Device level) → Select it
         * - Select button → Confirms current directory or selected storage
         *
         * Selectability:
         * - Real directories (not virtual locations like "Home")
         * - Storage volumes at Device level (internal/external storage)
         * - Select button enabled when at directory or storage selected
         *
         * Use case: Choose target directory for search, file operations, etc.
         */
        @Parcelize
        data object DirectorySingle : Selection() {
            @IgnoredOnParcel
            override val selectableConstraint: PickerConstraint = anyOf(
                PickerConstraint.IsDirectory,
                PickerConstraint.IsStorage,
            )

            @IgnoredOnParcel
            override val disabledConstraint: PickerConstraint = anyOf(
                PickerConstraint.IsFile,
                allOf(PickerConstraint.IsShortcut, PickerConstraint.HasShortcutId("trash")),
            )
        }

        /**
         * Select multiple directories via long-press selection mode.
         *
         * Interaction:
         * - Tap folder → Navigate into it
         * - Long-press folder → Toggle selection
         * - Tap storage volume → Navigate into it
         * - Long-press storage volume (at Device level) → Toggle selection
         * - Select button → Confirms selected directories/storages
         *
         * Selectability:
         * - Real directories (not virtual locations like "Home")
         * - Storage volumes at Device level (internal/external storage)
         * - Select button enabled when items selected or at directory
         *
         * Use case: Batch operations on multiple folders, select multiple storage volumes for search.
         */
        @Parcelize
        data object DirectoryMulti : Selection() {
            @IgnoredOnParcel
            override val selectableConstraint: PickerConstraint = anyOf(
                PickerConstraint.IsDirectory,
                PickerConstraint.IsStorage,
            )

            @IgnoredOnParcel
            override val disabledConstraint: PickerConstraint = anyOf(
                PickerConstraint.IsFile,
                allOf(PickerConstraint.IsShortcut, PickerConstraint.HasShortcutId("trash")),
            )
        }

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
        data object FileSingle : Selection() {
            @IgnoredOnParcel
            override val selectableConstraint: PickerConstraint = PickerConstraint.IsFile

            @IgnoredOnParcel
            override val disabledConstraint: PickerConstraint = PickerConstraint.None
        }

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
        data object FileMulti : Selection() {
            @IgnoredOnParcel
            override val selectableConstraint: PickerConstraint = PickerConstraint.IsFile

            @IgnoredOnParcel
            override val disabledConstraint: PickerConstraint = PickerConstraint.None
        }

        /**
         * Select multiple files AND directories via mixed interaction patterns.
         *
         * Interaction:
         * - Tap file → Toggle selection (checkbox appears)
         * - Tap folder → Navigate into it (normal navigation)
         * - Long-press file → Toggle selection (alternate way)
         * - Long-press folder → Toggle selection (only way to select folders)
         * - Select button → Confirms selected files and folders
         *
         * Selectability:
         * - Both files and directories
         * - Checkboxes visible on both types
         * - Select button enabled when at least one item selected
         * - Shows selection count: "Done (5 items)"
         *
         * UX Pattern:
         * - Files use tap-to-select (like FileMulti)
         * - Folders use long-press-to-select (like DirectoryMulti) + tap-to-navigate
         * - This combines existing patterns without ambiguity
         *
         * Use case: Select mixed content for operations, backups, sharing, etc.
         */
        @Parcelize
        data object MixedMulti : Selection() {
            @IgnoredOnParcel
            override val selectableConstraint: PickerConstraint = PickerConstraint.Any

            @IgnoredOnParcel
            override val disabledConstraint: PickerConstraint = PickerConstraint.None
        }

        /**
         * Save As mode: Select directory and provide filename.
         *
         * Interaction:
         * - Tap folder → Navigate into it
         * - Tap storage volume → Navigate into it
         * - Filename input field shown in picker bar
         * - Save button → Confirms current directory + entered filename
         *
         * Selectability:
         * - Real directories (like DirectorySingle)
         * - Save button enabled when at directory and filename is valid
         *
         * Use case: Save shared file to chosen location with custom name.
         */
        @Parcelize
        data class SaveAs(
            val suggestedFilename: String,
        ) : Selection() {
            @IgnoredOnParcel
            override val selectableConstraint: PickerConstraint = allOf(
                anyOf(PickerConstraint.IsDirectory, PickerConstraint.IsStorage),
                PickerConstraint.IsWritable,
            )

            @IgnoredOnParcel
            override val disabledConstraint: PickerConstraint = anyOf(
                PickerConstraint.IsFile,
                allOf(PickerConstraint.IsShortcut, PickerConstraint.HasShortcutId("trash")),
                not(PickerConstraint.IsWritable),
            )
        }

        /**
         * Returns true if this mode allows selecting multiple items.
         */
        val isMultiSelect: Boolean
            get() = when (this) {
                is DirectorySingle, is FileSingle, is SaveAs -> false
                is DirectoryMulti, is FileMulti, is MixedMulti -> true
            }

        /**
         * Returns true if this mode selects directories.
         */
        val selectsDirectories: Boolean
            get() = when (this) {
                is DirectorySingle, is DirectoryMulti, is MixedMulti, is SaveAs -> true
                is FileSingle, is FileMulti -> false
            }

        /**
         * Returns true if this mode selects files.
         */
        val selectsFiles: Boolean
            get() = when (this) {
                is DirectorySingle, is DirectoryMulti, is SaveAs -> false
                is FileSingle, is FileMulti, is MixedMulti -> true
            }

        /**
         * Returns true if tapping a file should instantly select it (and close the picker).
         */
        val instantFileSelection: Boolean
            get() = this is FileSingle
    }
}

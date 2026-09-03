package eu.darken.butler.workspace.ui.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.pkgs.installer.AppInstallFormat

/**
 * What a single file can be offered, shared by every surface that lists per-file actions, so the
 * same file is offered the same things in the Explorer, the Searcher and the Viewer.
 */
data class FileActionCapabilities(
    val installFormat: AppInstallFormat?,
    val archiveFormat: ArchiveFormat?,
    val isArchiveEntry: Boolean,
    val isText: Boolean,
    /** A file the system can be handed a `file://`/`content://` URI for. */
    val canHandOffToOtherApps: Boolean,
) {

    val isInstallable: Boolean get() = installFormat != null

    val isArchiveFile: Boolean get() = archiveFormat != null

    companion object {
        fun of(lookup: APathLookup<*>): FileActionCapabilities {
            val path = lookup.lookedUp
            val isArchiveEntry = path is ArchivePath
            // Every name-derived answer below is meaningless for a folder, and each one ends with an
            // executor being handed a directory: a folder named "app.apk" is not an install container.
            val isFile = lookup.fileType != FileType.DIRECTORY
            return FileActionCapabilities(
                installFormat = if (isFile) AppInstallFormat.fromFileName(path.name) else null,
                // An entry inside an archive is a nested archive at best, and cannot be opened as a
                // container where it lies.
                archiveFormat = if (isFile && !isArchiveEntry) ArchiveFormat.fromFileName(path.name) else null,
                isArchiveEntry = isArchiveEntry,
                isText = isFile && MimeInfo.fromFileName(path.name).isText,
                canHandOffToOtherApps = isFile && canHandOffToOtherApps(path),
            )
        }

        /**
         * The single definition of [FileActionCapabilities.canHandOffToOtherApps], for the surfaces
         * that only hold a path. It is the constraint OpenWithIntentUseCase and ShareIntentUseCase
         * impose: they resolve a URI for nothing else, and every other path type fails the hand-off.
         */
        fun canHandOffToOtherApps(path: APath<*>): Boolean = path is LocalPath
    }
}

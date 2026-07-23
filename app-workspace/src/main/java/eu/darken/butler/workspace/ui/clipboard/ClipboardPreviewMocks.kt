package eu.darken.butler.workspace.ui.clipboard

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType

/** Builds a lightweight file [LocalPathLookup] for Compose previews of clipboard entries. */
internal fun mockFileLookup(path: String): LocalPathLookup = LocalPathLookup(
    lookedUp = LocalPath.build(path),
    fileType = FileType.FILE,
    size = null,
    modifiedAt = null,
)

package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.metadata.FileMetadata
import eu.darken.butler.common.files.metadata.FileType

class FileTypeClassifier {

    fun classify(lookup: APathLookup<*>, metadata: FileMetadata? = null): ExplorerItem.Lookup = when (lookup.fileType) {
        FileType.DIRECTORY -> ExplorerItem.RegularDirectory(
            lookup = lookup,
            metadata = metadata,
        )
        FileType.SYMBOLIC_LINK -> ExplorerItem.SymbolicLink(
            lookup = lookup,
            mimeType = getMimeType(lookup.name),
            targetPath = lookup.target?.path,
            isBroken = lookup.target == null,
            metadata = metadata,
        )
        FileType.FILE -> classifyFile(lookup, metadata)
        else -> ExplorerItem.RegularFile(
            lookup = lookup,
            mimeType = getMimeType(lookup.name),
            metadata = metadata,
        )
    }

    private fun classifyFile(lookup: APathLookup<*>, metadata: FileMetadata?): ExplorerItem.Lookup {
        val mimeType = getMimeType(lookup.name)

        return when {
            else -> ExplorerItem.RegularFile(
                lookup = lookup,
                mimeType = mimeType,
                metadata = metadata,
            )
        }
    }

    internal fun getMimeType(fileName: String): MimeInfo = MimeInfo.fromFileName(fileName)

    private fun isArchiveType(mimeType: String): Boolean {
        return mimeType in setOf(
            "application/zip",
            "application/x-tar",
            "application/gzip",
            "application/x-7z-compressed",
            "application/vnd.rar"
        )
    }

    private fun isDocumentType(mimeType: String): Boolean {
        return mimeType in setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown"
        )
    }
}
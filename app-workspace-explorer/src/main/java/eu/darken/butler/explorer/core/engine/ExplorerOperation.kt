package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath

sealed interface ExplorerOperation {

    sealed interface FileOp : ExplorerOperation {
        data class CreateFolder(
            val parentPath: APath,
            val name: String,
        ) : FileOp

        data class Delete(
            val paths: Set<APath>,
            val recursive: Boolean = true,
        ) : FileOp

        data class Copy(
            val sources: Set<APath>,
            val destination: APath,
        ) : FileOp

        data class Move(
            val sources: Set<APath>,
            val destination: APath,
        ) : FileOp

        data class Rename(
            val path: APath,
            val newName: String,
        ) : FileOp
    }
}
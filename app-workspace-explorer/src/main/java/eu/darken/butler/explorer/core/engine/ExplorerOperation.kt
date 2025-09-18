package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.operations.OperationId
import kotlin.uuid.Uuid

sealed interface ExplorerOperation {
    val operationId: OperationId

    sealed interface FileOp : ExplorerOperation {
        data class Create(
            override val operationId: OperationId = Uuid.random(),
            val parentPath: APath,
            val name: String,
            val type: Type,
        ) : FileOp {
            enum class Type {
                FILE,
                FOLDER,
            }
        }

        data class Delete(
            override val operationId: OperationId = Uuid.random(),
            val paths: Set<APath>,
            val recursive: Boolean = true,
            val options: DeleteOptions = DeleteOptions(),
        ) : FileOp

        data class Copy(
            override val operationId: OperationId = Uuid.random(),
            val sources: Set<APath>,
            val destination: APath,
            val options: CopyOptions = CopyOptions(),
        ) : FileOp

        data class Move(
            override val operationId: OperationId = Uuid.random(),
            val sources: Set<APath>,
            val destination: APath,
            val options: MoveOptions = MoveOptions(),
        ) : FileOp

        data class Rename(
            override val operationId: OperationId = Uuid.random(),
            val path: APath,
            val newName: String,
        ) : FileOp
    }
}

data class DeleteOptions(
    val skipOnError: Boolean = false,
    val confirmPermanentDelete: Boolean = true,
)

data class CopyOptions(
    val preserveAttributes: Boolean = true,
    val preserveTimestamps: Boolean = true,
    val followSymlinks: Boolean = false,
)

data class MoveOptions(
    val preserveAttributes: Boolean = true,
    val preserveTimestamps: Boolean = true,
)
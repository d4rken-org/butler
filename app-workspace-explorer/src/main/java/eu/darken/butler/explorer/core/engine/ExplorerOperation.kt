package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.operations.ConflictStrategy
import eu.darken.butler.explorer.core.operations.OperationId
import kotlin.time.Duration
import kotlin.uuid.Uuid

sealed interface ExplorerOperation {
    val operationId: OperationId

    val canCancel: Boolean
        get() = true

    sealed interface FileOp : ExplorerOperation {
        data class CreateFolder(
            val parentPath: APath,
            val name: String,
            override val operationId: OperationId = Uuid.random(),
        ) : FileOp
        
        data class CreateFile(
            val parentPath: APath,
            val name: String,
            override val operationId: OperationId = Uuid.random(),
        ) : FileOp

        data class Delete(
            val paths: Set<APath>,
            val recursive: Boolean = true,
            val options: DeleteOptions = DeleteOptions(),
            override val operationId: OperationId = Uuid.random(),
        ) : FileOp

        data class Copy(
            val sources: Set<APath>,
            val destination: APath,
            val options: CopyOptions = CopyOptions(),
            override val operationId: OperationId = Uuid.random(),
        ) : FileOp

        data class Move(
            val sources: Set<APath>,
            val destination: APath,
            val options: MoveOptions = MoveOptions(),
            override val operationId: OperationId = Uuid.random(),
        ) : FileOp

        data class Rename(
            val path: APath,
            val newName: String,
            override val operationId: OperationId = Uuid.random(),
        ) : FileOp
        
        data class Compress(
            val sources: Set<APath>,
            val destination: APath,
            val format: CompressionFormat = CompressionFormat.ZIP,
            val options: CompressionOptions = CompressionOptions(),
            override val operationId: OperationId = Uuid.random(),
        ) : FileOp
        
        data class Extract(
            val archive: APath,
            val destination: APath,
            val options: ExtractionOptions = ExtractionOptions(),
            override val operationId: OperationId = Uuid.random(),
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
    val verifyChecksum: Boolean = false,
    val conflictStrategy: ConflictStrategy = ConflictStrategy.ASK,
    val followSymlinks: Boolean = false,
    val retryOnError: Int = 0,
    val timeout: Duration? = null,
)

data class MoveOptions(
    val preserveAttributes: Boolean = true,
    val conflictStrategy: ConflictStrategy = ConflictStrategy.ASK,
    val fallbackToCopy: Boolean = true, // if cross-filesystem move
    val retryOnError: Int = 0,
)

data class CompressionOptions(
    val compressionLevel: CompressionLevel = CompressionLevel.NORMAL,
    val includeHidden: Boolean = true,
    val followSymlinks: Boolean = false,
    val password: String? = null,
)

data class ExtractionOptions(
    val overwrite: Boolean = false,
    val preserveAttributes: Boolean = true,
    val password: String? = null,
    val conflictStrategy: ConflictStrategy = ConflictStrategy.ASK,
)

enum class CompressionFormat {
    ZIP,
    TAR,
    TAR_GZ,
    TAR_BZ2,
    TAR_XZ,
    SEVEN_Z,
}

enum class CompressionLevel {
    NONE,
    FAST,
    NORMAL,
    MAXIMUM,
}
package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.operations.Operation

sealed interface ExplorerCommand {
    data class Create(
        val parentPath: APath<*>,
        val name: String,
        val type: Type,
    ) : ExplorerCommand {
        enum class Type {
            FILE,
            DIRECTORY,
        }
    }

    data class Delete(
        val targets: Set<APath<*>>,
        val options: Options = Options(),
    ) : ExplorerCommand {
        data class Options(
            val skipOnError: Boolean = false,
            val confirmPermanentDelete: Boolean = true,
            val forcePermDelete: Boolean = false,
        )
    }

    data class Copy(
        val sources: Set<APath<*>>,
        val destination: APath<*>,
        val options: Options = Options(),
        /**
         * Optional semantic intent override surfaced in operation history
         * (e.g., [Operation.Metadata.Intent.PASTE_COPY] when invoked via paste).
         */
        val intent: Operation.Metadata.Intent? = null,
    ) : ExplorerCommand {
        data class Options(
            val preserveAttributes: Boolean = true,
            val followSymlinks: Boolean = false,
        )
    }

    data class Move(
        val sources: Set<APath<*>>,
        val destination: APath<*>,
        val options: Options = Options(),
        /**
         * Optional semantic intent override surfaced in operation history
         * (e.g., [Operation.Metadata.Intent.RENAME] when invoked via rename,
         * [Operation.Metadata.Intent.PASTE_MOVE] when invoked via paste-cut).
         */
        val intent: Operation.Metadata.Intent? = null,
    ) : ExplorerCommand {
        data class Options(
            val preserveAttributes: Boolean = true,
        )
    }

    data class CreateTextFile(
        val path: APath<*>,
        val content: String,
    ) : ExplorerCommand
}
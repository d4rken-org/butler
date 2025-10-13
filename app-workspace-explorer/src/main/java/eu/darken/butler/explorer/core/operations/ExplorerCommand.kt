package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath

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
        )
    }

    data class Copy(
        val sources: Set<APath<*>>,
        val destination: APath<*>,
        val options: Options = Options(),
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
    ) : ExplorerCommand {
        data class Options(
            val preserveAttributes: Boolean = true,
        )
    }
}
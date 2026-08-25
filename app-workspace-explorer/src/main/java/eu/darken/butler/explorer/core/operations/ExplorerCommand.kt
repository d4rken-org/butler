package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.CompressionPreset
import eu.darken.butler.workspace.core.operations.Operation
import kotlin.uuid.Uuid

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

    /**
     * Compress [sources] into a new archive named [archiveName] in [destinationDir].
     * Sources must all share a parent (their names are the archive's top-level entries).
     *
     * [overwriteConfirmed] must be true for the operation to replace an existing archive at the
     * target path; otherwise the operation aborts at commit rather than deleting the existing file.
     *
     * Note: because [Options] is intentionally not a data class (see below), this data class's
     * generated `equals`/`hashCode` compare [options] by reference. No code relies on structural
     * equality of a [Compress] command, so this is acceptable.
     */
    data class Compress(
        val sources: Set<APath<*>>,
        val destinationDir: APath<*>,
        val archiveName: String,
        val format: ArchiveFormat,
        val options: Options = Options(),
        val overwriteConfirmed: Boolean = false,
    ) : ExplorerCommand {
        /**
         * A non-null [password] enables AES-256 encryption (ZIP only). It is the single mutable
         * copy for the operation's lifetime: [CompressOperation] wipes it when done, so commands
         * retained in operation history hold only zeroes. Not a data class — generated
         * toString/equals would expose the password.
         */
        class Options(
            val preset: CompressionPreset = CompressionPreset.NORMAL,
            val password: CharArray? = null,
        ) {
            override fun toString(): String =
                "Options(preset=$preset, password=${if (password != null) "<set>" else "null"})"
        }
    }

    /**
     * Extract from [archive] into [destinationDir].
     * [entries] limits extraction to specific entry paths (relative to the archive root); null
     * extracts the whole archive into an archive-named subdirectory.
     */
    data class Extract(
        val archive: APath<*>,
        val destinationDir: APath<*>,
        val entries: Set<List<String>>? = null,
    ) : ExplorerCommand

    /**
     * Copies [source] (an archive on a forward-only backend) to [destinationDir] as an explicit,
     * user-initiated operation so it becomes browsable via random access. Written via a temp
     * sibling and committed atomically - a cancelled/failed download never leaves a truncated copy.
     */
    data class DownloadLocalCopy(
        val source: APath<*>,
        val destinationDir: APath<*>,
    ) : ExplorerCommand

    data class Restore(
        val rootItemIds: Set<Uuid> = emptySet(),
        val nestedItems: List<NestedTarget> = emptyList(),
        val restoredPaths: List<APath<*>>,
    ) : ExplorerCommand {
        data class NestedTarget(
            val parentId: Uuid,
            val relativePath: String,
        )
    }
}
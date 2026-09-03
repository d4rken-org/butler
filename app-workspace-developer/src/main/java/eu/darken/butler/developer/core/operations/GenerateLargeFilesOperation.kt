package eu.darken.butler.developer.core.operations

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.sharedresource.useRes
import eu.darken.butler.developer.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

class GenerateLargeFilesOperation @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val command: DeveloperCommand.GenerateLargeFiles,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
) : DeveloperOperation() {

    private val tag = logTag("Developer", "Workspace", workspaceId.shortTag, "Operation", "LargeFiles")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Developer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Storage
        override val title: CaString = R.string.developer_operation_large_files_title.toCaString()
        override val description: CaString = caString {
            it.getString(R.string.developer_operation_large_files_desc, command.basePath.path)
        }
    }

    override fun perform(operationContext: Operation.Context): Flow<State> = channelFlow {
        gatewaySwitch.useRes {
            log(tag, INFO) { "perform(): Starting large file generation at ${command.basePath}" }

            val targetDir = command.basePath.child(command.folderName)
            gatewaySwitch.createDir(targetDir, createParents = true)

            val sizes = listOf(
                1L * MB,     // 1 MB
                10L * MB,    // 10 MB
                100L * MB,   // 100 MB
                1L * GB,     // 1 GB
                2L * GB,     // 2 GB
                4L * GB,     // 4 GB
                8L * GB,     // 8 GB
            )

            val reportBuilder = TestDataOperationReport.Builder()
            val totalBytes = sizes.sum()
            var bytesWritten = 0L

            sizes.forEachIndexed { index, size ->
                currentCoroutineContext().ensureActive()

                val fileName = "file_${formatSize(size)}.bin"
                val filePath = targetDir.child(fileName)

                log(tag) { "Creating large file: $fileName ($size bytes)" }

                send(
                    State.Active(
                        startedAt = operationContext.startedAt,
                        primaryProgress = Progress.Data(
                            primary = R.string.developer_operation_large_files_creating.toCaString(fileName),
                            count = Progress.Count.Counter(index.toLong(), sizes.size.toLong()),
                        ),
                        secondaryProgress = Progress.Data(
                            primary = fileName.toCaString(),
                            count = Progress.Count.Size(bytesWritten, totalBytes),
                        ),
                    )
                )

                createLargeFile(filePath, size)

                bytesWritten += size
                reportBuilder.addFile(filePath, size)
                log(tag, INFO) { "Created large file: $fileName" }
            }

            reportBuilder.addDirectory(targetDir)

            send(
                State.Completed(
                    startedAt = operationContext.startedAt,
                    report = reportBuilder.build(),
                )
            )
            log(tag, INFO) { "Large file generation completed" }
        }
    }

    // Writing one byte past the end extends the file to its full size and leaves the skipped range
    // as a hole, so an 8GB entry costs a single block. Reading it back yields zeros.
    private suspend fun createLargeFile(
        path: APath<*>,
        size: Long,
    ) = withContext(dispatcherProvider.IO) {
        gatewaySwitch.createFile(path, createParents = false)
        gatewaySwitch.file(path, readWrite = true).use { handle ->
            handle.write(size - 1, ByteArray(1), 0, 1)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= GB -> "${bytes / GB}GB"
        bytes >= MB -> "${bytes / MB}MB"
        bytes >= KB -> "${bytes / KB}KB"
        else -> "${bytes}B"
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: DeveloperCommand.GenerateLargeFiles,
        ): GenerateLargeFilesOperation
    }

    companion object {
        private const val KB = 1024L
        private const val MB = 1024L * KB
        private const val GB = 1024L * MB
    }
}

package eu.darken.butler.developer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
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
import kotlin.random.Random

class GenerateTextFilesOperation @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val command: DeveloperCommand.GenerateTextFiles,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
) : DeveloperOperation() {

    private val tag = logTag("Developer", "Workspace", workspaceId.shortTag, "Operation", "TextFiles")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Developer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Description
        override val title: CaString = R.string.developer_operation_text_files_title.toCaString()
        override val description: CaString = caString {
            it.getString(R.string.developer_operation_text_files_desc, command.basePath.path)
        }
    }

    override fun perform(operationContext: Operation.Context): Flow<State> = channelFlow {
        gatewaySwitch.useRes {
            log(tag, INFO) { "perform(): Starting text file generation at ${command.basePath}" }

            val targetDir = command.basePath.child(command.folderName)
            gatewaySwitch.createDir(targetDir, createParents = true)

            val sizes = listOf(
                10L * KB,    // 10 KB
                100L * KB,   // 100 KB
                1L * MB,     // 1 MB
                10L * MB,    // 10 MB
                100L * MB,   // 100 MB
            )

            val reportBuilder = TestDataOperationReport.Builder()
            val totalBytes = sizes.sum()
            var bytesWritten = 0L

            sizes.forEachIndexed { index, size ->
                currentCoroutineContext().ensureActive()

                val fileName = "text_${formatSize(size)}.txt"
                val filePath = targetDir.child(fileName)

                log(tag) { "Creating text file: $fileName ($size bytes)" }

                send(
                    State.Active(
                        startedAt = operationContext.startedAt,
                        primaryProgress = Progress.Data(
                            primary = R.string.developer_operation_text_files_creating.toCaString(fileName),
                            count = Progress.Count.Counter(index.toLong(), sizes.size.toLong()),
                        ),
                        secondaryProgress = Progress.Data(
                            primary = fileName.toCaString(),
                            count = Progress.Count.Size(bytesWritten, totalBytes),
                        ),
                    )
                )

                createTextFile(filePath, size) { fileProgress ->
                    send(
                        State.Active(
                            startedAt = operationContext.startedAt,
                            primaryProgress = Progress.Data(
                                primary = R.string.developer_operation_text_files_creating.toCaString(fileName),
                                count = Progress.Count.Counter(index.toLong(), sizes.size.toLong()),
                            ),
                            secondaryProgress = Progress.Data(
                                primary = fileName.toCaString(),
                                count = Progress.Count.Size(bytesWritten + fileProgress, totalBytes),
                            ),
                        )
                    )
                }

                bytesWritten += size
                reportBuilder.addFile(filePath, size)
                log(tag, INFO) { "Created text file: $fileName" }
            }

            reportBuilder.addDirectory(targetDir)

            send(
                State.Completed(
                    startedAt = operationContext.startedAt,
                    report = reportBuilder.build(),
                )
            )
            log(tag, INFO) { "Text file generation completed" }
        }
    }

    private suspend fun createTextFile(
        path: APath<*>,
        size: Long,
        onProgress: suspend (Long) -> Unit,
    ) = withContext(dispatcherProvider.IO) {
        gatewaySwitch.createFile(path, createParents = false)
        gatewaySwitch.openOutputStream(path, append = false).bufferedWriter().use { writer ->
            var written = 0L
            var lineNum = 1
            var lastReportedProgress = 0L

            while (written < size) {
                currentCoroutineContext().ensureActive()

                val line = generateTextLine(lineNum++)
                writer.write(line)
                writer.newLine()
                written += line.length + 1

                // Report progress every 1MB
                if (written - lastReportedProgress >= PROGRESS_INTERVAL) {
                    onProgress(written)
                    lastReportedProgress = written
                }
            }
            onProgress(written)
        }
    }

    private fun generateTextLine(lineNumber: Int): String {
        val words = LOREM_WORDS.shuffled().take(Random.nextInt(5, 15))
        return "[$lineNumber] ${words.joinToString(" ")}"
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
            command: DeveloperCommand.GenerateTextFiles,
        ): GenerateTextFilesOperation
    }

    companion object {
        private const val KB = 1024L
        private const val MB = 1024L * KB
        private const val GB = 1024L * MB
        private const val PROGRESS_INTERVAL = 1L * MB // Report every 1MB

        private val LOREM_WORDS = listOf(
            "lorem", "ipsum", "dolor", "sit", "amet", "consectetur",
            "adipiscing", "elit", "sed", "do", "eiusmod", "tempor",
            "incididunt", "ut", "labore", "et", "dolore", "magna",
            "aliqua", "enim", "ad", "minim", "veniam", "quis",
            "nostrud", "exercitation", "ullamco", "laboris", "nisi",
            "butler", "android", "file", "explorer", "debug", "test",
        )
    }
}

package eu.darken.butler.developer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AccountTree
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

class GenerateNestedStructureOperation @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val command: DeveloperCommand.GenerateNestedStructure,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
) : DeveloperOperation() {

    private val tag = logTag("Developer", "Workspace", workspaceId.shortTag, "Operation", "NestedStructure")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Developer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.AccountTree
        override val title: CaString = R.string.developer_operation_nested_title.toCaString()
        override val description: CaString = caString {
            it.getString(R.string.developer_operation_nested_desc, command.basePath.path)
        }
    }

    override fun perform(operationContext: Operation.Context): Flow<State> = channelFlow {
        gatewaySwitch.useRes {
            log(tag, INFO) { "perform(): Starting nested structure generation at ${command.basePath}" }

            val targetDir = command.basePath.child(command.folderName)
            gatewaySwitch.createDir(targetDir, createParents = true)

            val totalDirs = calculateTotalDirs(command.depth, command.foldersPerLevel)
            val reportBuilder = TestDataOperationReport.Builder()
            reportBuilder.addDirectory(targetDir)

            var dirsCreated = 0
            var filesCreated = 0
            var totalSize = 0L

            suspend fun createNestedLevel(parent: APath<*>, currentDepth: Int) {
                if (currentDepth > command.depth) return

                currentCoroutineContext().ensureActive()

                // Create files in this directory
                repeat(command.filesPerFolder) { fileIndex ->
                    currentCoroutineContext().ensureActive()

                    val fileSize = Random.nextLong(1024, 50 * 1024) // 1KB to 50KB
                    val file = parent.child("file_${fileIndex + 1}.txt")

                    createTextFile(file, fileSize)
                    reportBuilder.addFile(file, fileSize)
                    filesCreated++
                    totalSize += fileSize
                }

                // Create subdirectories
                repeat(command.foldersPerLevel) { folderIndex ->
                    currentCoroutineContext().ensureActive()

                    val subDir = parent.child("folder_${folderIndex + 1}")
                    gatewaySwitch.createDir(subDir, createParents = false)
                    reportBuilder.addDirectory(subDir)
                    dirsCreated++

                    send(
                        State.Active(
                            startedAt = operationContext.startedAt,
                            primaryProgress = Progress.Data(
                                primary = R.string.developer_operation_nested_creating.toCaString(subDir.name),
                                count = Progress.Count.Counter(dirsCreated.toLong(), totalDirs.toLong()),
                            ),
                            secondaryProgress = Progress.Data(
                                primary = R.string.developer_operation_nested_files_created.toCaString(filesCreated),
                                count = Progress.Count.Percent(dirsCreated.toLong(), totalDirs.toLong()),
                            ),
                        )
                    )

                    createNestedLevel(subDir, currentDepth + 1)
                }
            }

            send(
                State.Active(
                    startedAt = operationContext.startedAt,
                    primaryProgress = Progress.Data(
                        primary = R.string.developer_operation_nested_starting.toCaString(),
                        count = Progress.Count.Counter(0, totalDirs.toLong()),
                    ),
                )
            )

            createNestedLevel(targetDir, 1)

            send(
                State.Completed(
                    startedAt = operationContext.startedAt,
                    report = reportBuilder.build(),
                )
            )
            log(tag, INFO) { "Nested structure generation completed: $dirsCreated dirs, $filesCreated files" }
        }
    }

    private suspend fun createTextFile(path: APath<*>, size: Long) = withContext(dispatcherProvider.IO) {
        gatewaySwitch.createFile(path, createParents = false)
        gatewaySwitch.openOutputStream(path, append = false).bufferedWriter().use { writer ->
            var written = 0L
            var lineNum = 1
            while (written < size) {
                val line = generateTextLine(lineNum++)
                writer.write(line)
                writer.newLine()
                written += line.length + 1
            }
        }
    }

    private fun generateTextLine(lineNumber: Int): String {
        val words = LOREM_WORDS.shuffled().take(Random.nextInt(5, 15))
        return "[$lineNumber] ${words.joinToString(" ")}"
    }

    private fun calculateTotalDirs(depth: Int, foldersPerLevel: Int): Int {
        var total = 0
        var current = 1
        repeat(depth) {
            current *= foldersPerLevel
            total += current
        }
        return total
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: DeveloperCommand.GenerateNestedStructure,
        ): GenerateNestedStructureOperation
    }

    companion object {
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

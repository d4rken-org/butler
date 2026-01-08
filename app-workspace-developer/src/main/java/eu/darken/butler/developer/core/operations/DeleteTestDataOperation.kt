package eu.darken.butler.developer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DeleteForever
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
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.metadata.FileType
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

class DeleteTestDataOperation @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val command: DeveloperCommand.DeleteTestData,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
) : DeveloperOperation() {

    private val tag = logTag("Developer", "Workspace", workspaceId.shortTag, "Operation", "DeleteTestData")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Developer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.DeleteForever
        override val title: CaString = R.string.developer_operation_delete_testdata_title.toCaString()
        override val description: CaString = caString {
            it.getString(R.string.developer_operation_delete_testdata_desc, command.basePath.path)
        }
    }

    override fun perform(operationContext: Operation.Context): Flow<State> = channelFlow {
        gatewaySwitch.useRes {
            log(tag, INFO) { "perform(): Starting test data deletion at ${command.basePath}" }

            val reportBuilder = DeleteTestDataReport.Builder()
            val dirsToDelete = mutableListOf<String>()

            if (command.deleteLargeFiles) dirsToDelete.add(LARGE_FILES_DIR)
            if (command.deleteNestedStructure) dirsToDelete.add(NESTED_STRUCTURE_DIR)
            if (command.deleteTextFiles) dirsToDelete.add(TEXT_FILES_DIR)

            val totalDirs = dirsToDelete.size
            var processedDirs = 0

            for (dirName in dirsToDelete) {
                currentCoroutineContext().ensureActive()

                val targetDir = command.basePath.child(dirName)
                log(tag) { "Checking directory: $targetDir" }

                send(
                    State.Active(
                        startedAt = operationContext.startedAt,
                        primaryProgress = Progress.Data(
                            primary = R.string.developer_operation_delete_testdata_checking.toCaString(dirName),
                            count = Progress.Count.Counter(processedDirs.toLong(), totalDirs.toLong()),
                        ),
                    )
                )

                if (!gatewaySwitch.exists(targetDir)) {
                    log(tag) { "Directory does not exist, skipping: $targetDir" }
                    processedDirs++
                    continue
                }

                send(
                    State.Active(
                        startedAt = operationContext.startedAt,
                        primaryProgress = Progress.Data(
                            primary = R.string.developer_operation_delete_testdata_scanning.toCaString(dirName),
                            count = Progress.Count.Counter(processedDirs.toLong(), totalDirs.toLong()),
                        ),
                    )
                )

                val (fileCount, dirCount, totalSize) = withContext(dispatcherProvider.IO) {
                    countDirectoryContents(targetDir)
                }
                log(tag, INFO) { "Directory $dirName contains $fileCount files, $dirCount dirs, $totalSize bytes" }

                send(
                    State.Active(
                        startedAt = operationContext.startedAt,
                        primaryProgress = Progress.Data(
                            primary = R.string.developer_operation_delete_testdata_deleting.toCaString(dirName),
                            count = Progress.Count.Counter(processedDirs.toLong(), totalDirs.toLong()),
                        ),
                    )
                )

                log(tag) { "Deleting directory: $targetDir" }
                val deleted = gatewaySwitch.delete(targetDir, recursive = true)

                if (deleted) {
                    repeat(fileCount) { reportBuilder.addDeletedFile(targetDir, totalSize / fileCount.coerceAtLeast(1)) }
                    repeat(dirCount + 1) { reportBuilder.addDeletedDirectory(targetDir) }
                    log(tag, INFO) { "Successfully deleted: $dirName" }
                } else {
                    log(tag, WARN) { "Failed to delete: $dirName" }
                }

                processedDirs++
            }

            send(
                State.Completed(
                    startedAt = operationContext.startedAt,
                    report = reportBuilder.build(),
                )
            )
            log(tag, INFO) { "Test data deletion completed" }
        }
    }

    private suspend fun countDirectoryContents(path: eu.darken.butler.common.files.APath<*>): Triple<Int, Int, Long> {
        var fileCount = 0
        var dirCount = 0
        var totalSize = 0L

        suspend fun walkDirectory(dir: eu.darken.butler.common.files.APath<*>) {
            currentCoroutineContext().ensureActive()

            val children = try {
                gatewaySwitch.lookupFiles(dir, LookupOptions.BASE)
            } catch (e: Exception) {
                log(tag, WARN) { "Failed to list directory: $dir - ${e.message}" }
                return
            }

            for (child in children) {
                if (child.fileType == FileType.DIRECTORY) {
                    dirCount++
                    walkDirectory(child.lookedUp)
                } else {
                    fileCount++
                    totalSize += child.size ?: 0L
                }
            }
        }

        walkDirectory(path)
        return Triple(fileCount, dirCount, totalSize)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: DeveloperCommand.DeleteTestData,
        ): DeleteTestDataOperation
    }

    companion object {
        private const val LARGE_FILES_DIR = "aButlerLargeFiles"
        private const val NESTED_STRUCTURE_DIR = "aButlerNestedData"
        private const val TEXT_FILES_DIR = "aButlerTextFiles"
    }
}

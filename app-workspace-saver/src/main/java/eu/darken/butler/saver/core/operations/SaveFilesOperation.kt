package eu.darken.butler.saver.core.operations

import android.content.Context
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Save
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Instant

class SaveFilesOperation @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val command: Command,
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
) : Operation {

    private val tag = logTag("Saver", "Operation", "SaveFiles", workspaceId.shortTag)

    data class Command(
        val sources: List<SourceFile>,
        val targetDirectory: APath<*>,
    ) {
        data class SourceFile(
            val uri: Uri,
            val filename: String,
            val size: Long?,
        )
    }

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin: Operation.Metadata.Origin = Operation.Metadata.Origin.Saver(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Save
        override val title: CaString = R.string.saver_operation_title.toCaString()
        override val description: CaString = caString { cx ->
            cx.getQuantityString2(
                R.plurals.saver_operation_description,
                command.sources.size,
                command.sources.size,
                command.targetDirectory.userReadablePath.get(cx),
            )
        }
    }

    override fun perform(operationContext: Operation.Context): Flow<Operation.State> = flow {
        log(tag, INFO) { "perform(): Starting save of ${command.sources.size} files to ${command.targetDirectory}" }

        var stateActive = State.Active(startedAt = operationContext.startedAt)
        emit(stateActive)

        val results = mutableListOf<SaveFilesReport.FileResult>()

        command.sources.forEachIndexed { index, source ->
            // Check for cancellation between files
            currentCoroutineContext().ensureActive()

            log(tag) { "Saving file ${index + 1}/${command.sources.size}: ${source.filename}" }

            // Update progress
            stateActive = stateActive.copy(
                primaryProgress = Progress.Data(
                    primary = caString { "${index + 1} / ${command.sources.size}" },
                    secondary = source.filename.toCaString(),
                    count = Progress.Count.Counter(index + 1, command.sources.size),
                ),
            )
            emit(stateActive)

            // Execute single file save
            val result = saveFile(source, command.targetDirectory)
            results.add(result)

            when (result) {
                is SaveFilesReport.FileResult.Success -> {
                    log(tag, INFO) { "Successfully saved: ${source.filename} -> ${result.savedPath}" }
                }
                is SaveFilesReport.FileResult.Error -> {
                    log(tag, ERROR) { "Failed to save: ${source.filename} - ${result.error}" }
                }
            }
        }

        // Build report
        val report = SaveFilesReport(results)
        log(tag, INFO) { "Save operation completed: ${report.successes.size} succeeded, ${report.errors.size} failed" }

        emit(
            State.Completed(
                startedAt = operationContext.startedAt,
                report = report,
            )
        )
    }

    private suspend fun saveFile(
        source: Command.SourceFile,
        targetDirectory: APath<*>,
    ): SaveFilesReport.FileResult {
        return try {
            // Open input stream from ContentResolver
            val inputStream = context.contentResolver.openInputStream(source.uri)
                ?: return SaveFilesReport.FileResult.Error(
                    filename = source.filename,
                    error = IllegalStateException("Failed to open input stream for ${source.uri}"),
                )

            // Create target path (handle filename conflicts)
            val targetPath = resolveUniqueFilename(targetDirectory, source.filename)

            // Create file and write
            gatewaySwitch.createFile(targetPath, createParents = false)

            var bytesWritten = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            gatewaySwitch.openOutputStream(targetPath, append = false).use { outputStream ->
                inputStream.use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead
                    }
                    outputStream.flush()
                }
            }

            SaveFilesReport.FileResult.Success(
                filename = source.filename,
                savedPath = targetPath,
                bytes = bytesWritten,
            )
        } catch (e: SecurityException) {
            log(tag, ERROR) { "Security exception saving ${source.filename}: ${e.asLog()}" }
            SaveFilesReport.FileResult.Error(
                filename = source.filename,
                error = e,
            )
        } catch (e: Exception) {
            log(tag, ERROR) { "Error saving ${source.filename}: ${e.asLog()}" }
            SaveFilesReport.FileResult.Error(
                filename = source.filename,
                error = e,
            )
        }
    }

    private suspend fun resolveUniqueFilename(directory: APath<*>, filename: String): APath<*> {
        var targetPath = directory.child(filename)

        if (!gatewaySwitch.exists(targetPath)) {
            return targetPath
        }

        // File exists, add suffix
        val dotIndex = filename.lastIndexOf('.')
        val baseName = if (dotIndex > 0) filename.substring(0, dotIndex) else filename
        val extension = if (dotIndex > 0) filename.substring(dotIndex) else ""

        var counter = 1
        while (gatewaySwitch.exists(targetPath)) {
            val newFilename = "${baseName}_$counter$extension"
            targetPath = directory.child(newFilename)
            counter++

            if (counter > 999) {
                // Safety limit
                throw IllegalStateException("Could not find unique filename for $filename")
            }
        }

        log(tag) { "Resolved unique filename: $filename -> ${targetPath.name}" }
        return targetPath
    }

    sealed interface State : Operation.State {
        data class Active(
            override val startedAt: Instant,
            override val primaryProgress: Progress.Data = Progress.Data(),
            override val secondaryProgress: Progress.Data? = null,
        ) : State, Operation.State.Active

        data class Completed(
            override val startedAt: Instant,
            override val completedAt: Instant = Clock.System.now(),
            override val error: Throwable? = null,
            override val report: SaveFilesReport,
        ) : State, Operation.State.Completed {
            override val summary: CaString get() = report.summary
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, command: Command): SaveFilesOperation
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}

package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.IOException

data class CopyOperation(
    val state: State,
    val from: APath,
    val to: APath,
    val bytesCopied: Long = 0L,
    val bytesTotal: Long = 0L,
    val error: Exception? = null
) {
    enum class State {
        CALCULATING_SIZE,
        COPYING,
        COMPLETED,
        FAILED
    }

    val progress: Float = if (bytesTotal > 0) bytesCopied.toFloat() / bytesTotal else 0f
}

fun APath.copyOperation(
    gateway: GatewaySwitch,
    target: APath,
    overwrite: Boolean = false
): Flow<CopyOperation> = flow {
    val source = this@copyOperation

    var progress = CopyOperation(
        state = CopyOperation.State.CALCULATING_SIZE,
        from = source,
        to = target,
    )
    emit(progress)

    // Check if source exists
    if (!source.exists(gateway)) {
        progress = progress.copy(
            state = CopyOperation.State.FAILED,
            error = IOException("Source does not exist: $source")
        )
        emit(progress)
        return@flow
    }

    val sourceLookup = gateway.lookup(source)

    // This function only handles single files - directories must be handled by CopyOperationHandler
    if (sourceLookup.isDirectory) {
        progress = progress.copy(
            state = CopyOperation.State.FAILED,
            error = IOException("Directory copying not supported: $source. Use CopyOperationHandler for directory operations.")
        )
        emit(progress)
        return@flow
    }

    val totalBytes = sourceLookup.size

    progress = progress.copy(
        state = CopyOperation.State.COPYING,
        bytesTotal = totalBytes,
    )
    emit(progress)

    try {
        // Only handle single files - directories should be handled by CopyOperationHandler
        source.copyFileOperation(gateway, target, overwrite, totalBytes).collect {
            progress = it
            emit(it)
        }

        progress = progress.copy(
            state = CopyOperation.State.COMPLETED,
            bytesCopied = totalBytes,
            bytesTotal = totalBytes,
        )
        emit(progress)
    } catch (e: Exception) {
        progress = progress.copy(
            state = CopyOperation.State.FAILED,
            bytesCopied = totalBytes,
            bytesTotal = totalBytes,
            error = e
        )
        emit(progress)
    }
}

private fun APath.copyFileOperation(
    gateway: GatewaySwitch,
    targetPath: APath,
    overwrite: Boolean,
    totalBytes: Long
): Flow<CopyOperation> = flow {
    val sourcePath = this@copyFileOperation

    if (!overwrite && targetPath.exists(gateway)) {
        throw IOException("Target already exists: $targetPath")
    }

    var progress = CopyOperation(
        state = CopyOperation.State.COPYING,
        bytesTotal = totalBytes,
        from = sourcePath,
        to = targetPath,
    )

    // Create the target file
    targetPath.createFileIfNecessary(gateway)

    // Open source and target file handles
    val sourceHandle = gateway.file(this@copyFileOperation, readWrite = false)
    val targetHandle = gateway.file(targetPath, readWrite = true)

    sourceHandle.use { source ->
        targetHandle.use { target ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesCopied = 0L

            while (true) {
                val bytesRead = source.read(
                    fileOffset = bytesCopied,
                    array = buffer,
                    arrayOffset = 0,
                    byteCount = buffer.size
                )
                if (bytesRead == -1) break

                target.write(
                    fileOffset = bytesCopied,
                    array = buffer,
                    arrayOffset = 0,
                    byteCount = bytesRead
                )
                bytesCopied += bytesRead

                progress = progress.copy(
                    bytesCopied = bytesCopied,
                )
                emit(progress)
            }
        }
    }

    // Copy attributes
    try {
        val sourceLookup = gateway.lookup(this@copyFileOperation)
        val modifiedAt = sourceLookup.modifiedAt
        targetPath.setModifiedAt(gateway, modifiedAt)
    } catch (e: Exception) {
        log(WARN) { "Failed to copy attributes: ${e.message}" }
    }
}


private const val DEFAULT_BUFFER_SIZE = 64 * 1024 // 64KB buffer
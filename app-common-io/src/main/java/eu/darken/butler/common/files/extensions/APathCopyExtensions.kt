package eu.darken.butler.common.files.extensions

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.FileHandle
import okio.IOException

data class CopyOperation(
    val state: State,
    val bytesCopied: Long = 0L,
    val totalBytes: Long = 0L,
    val currentPath: APath? = null,
    val result: APath? = null,
    val error: Exception? = null
) {
    enum class State {
        CALCULATING_SIZE,
        COPYING,
        COMPLETED,
        FAILED
    }
    
    val progress: Float = if (totalBytes > 0) bytesCopied.toFloat() / totalBytes else 0f
}

fun <T : APath> T.copyOperation(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    target: T,
    overwrite: Boolean = false
): Flow<CopyOperation> = flow {
    val source = this@copyOperation
    
    // Emit calculating state
    emit(CopyOperation(state = CopyOperation.State.CALCULATING_SIZE))
    
    // Check if source exists
    if (!source.exists(gateway)) {
        emit(CopyOperation(
            state = CopyOperation.State.FAILED,
            error = IOException("Source does not exist: $source")
        ))
        return@flow
    }
    
    val sourceLookup = gateway.lookup(source)
    
    // Calculate total size
    val totalBytes = if (sourceLookup.isDirectory) {
        source.du(gateway)
    } else {
        sourceLookup.size
    }
    
    emit(CopyOperation(
        state = CopyOperation.State.COPYING,
        totalBytes = totalBytes,
        currentPath = source
    ))
    
    try {
        // Handle directories
        if (sourceLookup.isDirectory) {
            source.copyDirectoryOperation(gateway, target, overwrite, totalBytes).collect { emit(it) }
        } else {
            // Handle files
            source.copyFileOperation(gateway, target, overwrite, totalBytes).collect { emit(it) }
        }
        
        // Emit completion
        emit(CopyOperation(
            state = CopyOperation.State.COMPLETED,
            bytesCopied = totalBytes,
            totalBytes = totalBytes,
            result = target
        ))
    } catch (e: Exception) {
        emit(CopyOperation(
            state = CopyOperation.State.FAILED,
            bytesCopied = 0L,
            totalBytes = totalBytes,
            error = e
        ))
    }
}

private fun <T : APath> T.copyFileOperation(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    target: T,
    overwrite: Boolean,
    totalBytes: Long
): Flow<CopyOperation> = flow {
    if (!overwrite && target.exists(gateway)) {
        throw IOException("Target already exists: $target")
    }
    
    // Create the target file
    target.createFileIfNecessary(gateway)
    
    // Open source and target file handles
    val sourceHandle = gateway.file(this@copyFileOperation, readWrite = false)
    val targetHandle = gateway.file(target, readWrite = true)
    
    sourceHandle.use { source ->
        targetHandle.use { target ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesCopied = 0L
            
            while (true) {
                val bytesRead = source.read(bytesCopied, buffer, 0, buffer.size)
                if (bytesRead == -1) break
                
                target.write(bytesCopied, buffer, 0, bytesRead)
                bytesCopied += bytesRead
                
                // Emit progress
                emit(CopyOperation(
                    state = CopyOperation.State.COPYING,
                    bytesCopied = bytesCopied,
                    totalBytes = totalBytes,
                    currentPath = this@copyFileOperation
                ))
            }
        }
    }
    
    // Copy attributes
    try {
        val sourceLookup = gateway.lookup(this@copyFileOperation)
        val modifiedAt = sourceLookup.modifiedAt
        if (modifiedAt != null) {
            target.setModifiedAt(gateway, modifiedAt)
        }
    } catch (e: Exception) {
        log(WARN) { "Failed to copy attributes: ${e.message}" }
    }
}

private fun <T : APath> T.copyDirectoryOperation(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    target: T,
    overwrite: Boolean,
    totalBytes: Long
): Flow<CopyOperation> = flow {
    // Create target directory
    target.createDirIfNecessary(gateway)
    
    var bytesCopied = 0L
    
    // Copy all files in directory
    val files = gateway.listFiles(this@copyDirectoryOperation)
    for (file in files) {
        val targetFile = target.child(file.name) as T
        
        @Suppress("UNCHECKED_CAST")
        (file as T).copyOperation(gateway, targetFile, overwrite)
            .collect { progress ->
                when (progress.state) {
                    CopyOperation.State.COPYING -> {
                        emit(CopyOperation(
                            state = CopyOperation.State.COPYING,
                            bytesCopied = bytesCopied + progress.bytesCopied,
                            totalBytes = totalBytes,
                            currentPath = progress.currentPath
                        ))
                    }
                    CopyOperation.State.COMPLETED -> {
                        bytesCopied += progress.totalBytes
                    }
                    CopyOperation.State.FAILED -> throw progress.error!!
                    else -> {} // Skip CALCULATING_SIZE
                }
            }
    }
}

private const val DEFAULT_BUFFER_SIZE = 64 * 1024 // 64KB buffer
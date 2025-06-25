package eu.darken.butler.explorer.ui.browser

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class FileSystemRepository {

    suspend fun loadDirectory(path: APath): Flow<List<FileItem>> = flow {
        // TODO: Implement real file system integration
        // For now, return empty list to prevent compilation errors
        emit(emptyList<FileItem>())
    }.flowOn(Dispatchers.IO)

    suspend fun refreshDirectory(path: APath): List<FileItem> = withContext(Dispatchers.IO) {
        // TODO: Implement real file system integration
        emptyList()
    }

    suspend fun getDirectoryItemCount(path: APath): Int? = withContext(Dispatchers.IO) {
        // TODO: Implement real file system integration
        null
    }

    suspend fun isPathValid(path: APath): Boolean = withContext(Dispatchers.IO) {
        // TODO: Implement real file system integration
        true
    }
}
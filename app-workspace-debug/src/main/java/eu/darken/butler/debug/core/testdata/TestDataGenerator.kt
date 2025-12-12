package eu.darken.butler.debug.core.testdata

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class TestDataGenerator @Inject constructor() {

    sealed interface Progress {
        data class Creating(val current: Int, val total: Int, val name: String) : Progress
        data class Completed(val filesCreated: Int, val totalSize: Long) : Progress
        data class Error(val message: String, val exception: Throwable?) : Progress
    }

    fun generateLargeFiles(
        baseDir: File,
        folderName: String = "aButlerLargeFiles",
    ): Flow<Progress> = flow {
        val targetDir = File(baseDir, folderName)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            emit(Progress.Error("Failed to create directory: $targetDir", null))
            return@flow
        }

        val sizes = listOf(
            1L * MB,     // 1 MB
            10L * MB,    // 10 MB
            100L * MB,   // 100 MB
            1L * GB,     // 1 GB
            2L * GB,     // 2 GB
            4L * GB,     // 4 GB
            8L * GB,     // 8 GB
        )

        var totalSize = 0L
        sizes.forEachIndexed { index, size ->
            val fileName = "file_${formatSize(size)}.bin"
            emit(Progress.Creating(index + 1, sizes.size, fileName))

            try {
                createLargeFile(File(targetDir, fileName), size)
                totalSize += size
                log(TAG, INFO) { "Created large file: $fileName ($size bytes)" }
            } catch (e: Exception) {
                emit(Progress.Error("Failed to create $fileName: ${e.message}", e))
                return@flow
            }
        }

        emit(Progress.Completed(sizes.size, totalSize))
    }

    fun generateNestedStructure(
        baseDir: File,
        folderName: String = "aButlerNestedData",
        depth: Int = 6,
        foldersPerLevel: Int = 3,
        filesPerFolder: Int = 3,
    ): Flow<Progress> = flow {
        val targetDir = File(baseDir, folderName)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            emit(Progress.Error("Failed to create directory: $targetDir", null))
            return@flow
        }

        var totalFiles = 0
        var totalSize = 0L
        val totalDirs = calculateTotalDirs(depth, foldersPerLevel)

        suspend fun createNestedLevel(
            parent: File,
            currentDepth: Int,
            currentDir: Int,
        ): Int {
            var dirCount = currentDir
            if (currentDepth > depth) return dirCount

            // Create files in this directory
            repeat(filesPerFolder) { fileIndex ->
                val fileSize = Random.nextLong(1024, 50 * 1024) // 1KB to 50KB
                val file = File(parent, "file_${fileIndex + 1}.txt")
                createTextFile(file, fileSize)
                totalFiles++
                totalSize += fileSize
            }

            // Create subdirectories
            repeat(foldersPerLevel) { folderIndex ->
                val subDir = File(parent, "folder_${folderIndex + 1}")
                if (subDir.mkdirs()) {
                    dirCount++
                    emit(Progress.Creating(dirCount, totalDirs, subDir.name))
                    dirCount = createNestedLevel(subDir, currentDepth + 1, dirCount)
                }
            }

            return dirCount
        }

        try {
            createNestedLevel(targetDir, 1, 0)
            emit(Progress.Completed(totalFiles, totalSize))
        } catch (e: Exception) {
            emit(Progress.Error("Failed to create nested structure: ${e.message}", e))
        }
    }

    fun generateTextFiles(
        baseDir: File,
        folderName: String = "aButlerTextFiles",
    ): Flow<Progress> = flow {
        val targetDir = File(baseDir, folderName)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            emit(Progress.Error("Failed to create directory: $targetDir", null))
            return@flow
        }

        val sizes = listOf(
            10L * KB,    // 10 KB
            100L * KB,   // 100 KB
            1L * MB,     // 1 MB
            10L * MB,    // 10 MB
            100L * MB,   // 100 MB
        )

        var totalSize = 0L
        sizes.forEachIndexed { index, size ->
            val fileName = "text_${formatSize(size)}.txt"
            emit(Progress.Creating(index + 1, sizes.size, fileName))

            try {
                createTextFile(File(targetDir, fileName), size)
                totalSize += size
                log(TAG) { "Created text file: $fileName ($size bytes)" }
            } catch (e: Exception) {
                emit(Progress.Error("Failed to create $fileName: ${e.message}", e))
                return@flow
            }
        }

        emit(Progress.Completed(sizes.size, totalSize))
    }

    private suspend fun createLargeFile(file: File, size: Long) = withContext(Dispatchers.IO) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(size)
            // Write random data at intervals to ensure the file has actual content
            val buffer = ByteArray(CHUNK_SIZE)
            var position = 0L
            while (position < size) {
                Random.nextBytes(buffer)
                raf.seek(position)
                val writeSize = minOf(CHUNK_SIZE.toLong(), size - position).toInt()
                raf.write(buffer, 0, writeSize)
                position += SPARSE_INTERVAL
            }
        }
    }

    private suspend fun createTextFile(file: File, size: Long) = withContext(Dispatchers.IO) {
        file.outputStream().bufferedWriter().use { writer ->
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

    private fun formatSize(bytes: Long): String = when {
        bytes >= GB -> "${bytes / GB}GB"
        bytes >= MB -> "${bytes / MB}MB"
        bytes >= KB -> "${bytes / KB}KB"
        else -> "${bytes}B"
    }

    companion object {
        private val TAG = logTag("Debug", "TestDataGenerator")
        private const val KB = 1024L
        private const val MB = 1024L * KB
        private const val GB = 1024L * MB
        private const val CHUNK_SIZE = 1024 * 1024 // 1MB chunks
        private const val SPARSE_INTERVAL = 10L * MB // Write every 10MB for sparse files

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

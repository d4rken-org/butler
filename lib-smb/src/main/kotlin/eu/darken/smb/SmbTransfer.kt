package eu.darken.smb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Each emission owns its byte array. Cancellation closes the file, including early collection. */
public fun SmbShare.readChunks(
    path: SmbPath,
    offset: Long = 0,
    chunkSize: Int = 64 * 1024,
): Flow<ByteArray> {
    require(offset >= 0)
    require(chunkSize in 1..1024 * 1024)
    return flow {
        openFile(path).use { file ->
            var position = offset
            while (true) {
                val buffer = ByteArray(chunkSize)
                val count = file.read(position, buffer)
                if (count == -1) break
                emit(if (count == buffer.size) buffer else buffer.copyOf(count))
                position = Math.addExact(position, count.toLong())
            }
        }
    }
}

/** A failed or cancelled write may leave a partial file. No mutation is automatically replayed. */
public suspend fun SmbShare.writeChunks(
    path: SmbPath,
    chunks: Flow<ByteArray>,
    mode: SmbOpenMode = SmbOpenMode.CREATE_NEW,
): Long {
    require(mode != SmbOpenMode.READ)
    return openFile(path, mode).use { file ->
        var position = 0L
        chunks.collect { bytes ->
            file.write(position, bytes)
            position = Math.addExact(position, bytes.size.toLong())
        }
        file.flush()
        position
    }
}

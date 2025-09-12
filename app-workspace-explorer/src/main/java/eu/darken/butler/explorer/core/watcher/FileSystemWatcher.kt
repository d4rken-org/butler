package eu.darken.butler.explorer.core.watcher

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Watches file system changes and emits events for directory updates.
 * Provides real-time notifications when files are created, deleted, or modified.
 */
interface FileSystemWatcher {
    /**
     * Start watching a directory for changes.
     * @param path The directory path to watch
     * @return Flow of file system events for this path
     */
    fun watch(path: APath): Flow<FileSystemEvent>
    
    /**
     * Start watching a directory without receiving events.
     * Useful for preemptively setting up watchers.
     */
    suspend fun startWatching(path: APath)
    
    /**
     * Stop watching a directory.
     */
    suspend fun stopWatching(path: APath)
    
    /**
     * Stop all active watchers.
     */
    suspend fun stopAll()
    
    /**
     * Get the current number of active watchers.
     */
    fun getActiveWatcherCount(): Int
}

/**
 * Events emitted by the file system watcher.
 */
sealed class FileSystemEvent {
    abstract val path: APath
    abstract val timestamp: Instant
    
    data class FileCreated(
        override val path: APath,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : FileSystemEvent()
    
    data class FileDeleted(
        override val path: APath,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : FileSystemEvent()
    
    data class FileModified(
        override val path: APath,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : FileSystemEvent()
    
    data class FileRenamed(
        override val path: APath,
        val newPath: APath,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : FileSystemEvent()
    
    data class DirectoryChanged(
        override val path: APath,
        val changeType: ChangeType,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : FileSystemEvent() {
        enum class ChangeType {
            CONTENT_CHANGED,
            PERMISSIONS_CHANGED,
            UNKNOWN
        }
    }
    
    /**
     * Too many changes detected, a full refresh is needed.
     */
    data class MassiveChange(
        override val path: APath,
        val changeCount: Int,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : FileSystemEvent()
    
    /**
     * Watching has failed for this path.
     */
    data class WatchError(
        override val path: APath,
        val error: Throwable,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : FileSystemEvent()
}
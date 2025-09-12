package eu.darken.butler.explorer.core.watcher

import android.os.Build
import android.os.FileObserver
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Android implementation of FileSystemWatcher using FileObserver API.
 * Handles file system change notifications with batching and error recovery.
 */
@Singleton
class AndroidFileSystemWatcher @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
) : FileSystemWatcher {
    
    private val observers = ConcurrentHashMap<APath, WatcherState>()
    private val mutex = Mutex()
    
    private data class WatcherState(
        val observer: FileObserver,
        var referenceCount: Int = 1,
        var lastEventTime: Long = 0L,
    )
    
    override fun watch(path: APath): Flow<FileSystemEvent> = callbackFlow {
        log(TAG, DEBUG) { "Starting to watch: $path" }
        
        // Only LocalPath is supported for FileObserver
        if (path !is LocalPath) {
            log(TAG, WARN) { "Cannot watch non-local path: $path" }
            send(FileSystemEvent.WatchError(
                path = path,
                error = UnsupportedOperationException("FileObserver only supports local paths")
            ))
            close()
            return@callbackFlow
        }
        
        val file = File(path.path)
        if (!file.exists()) {
            log(TAG, WARN) { "Path does not exist: $path" }
            send(FileSystemEvent.WatchError(
                path = path,
                error = IllegalArgumentException("Path does not exist: $path")
            ))
            close()
            return@callbackFlow
        }
        
        // Event batching to prevent flooding
        val eventBuffer = mutableListOf<FileSystemEvent>()
        var lastFlushTime = System.currentTimeMillis()
        
        val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+ constructor with File parameter
            object : FileObserver(file, ALL_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    handleEvent(event, path, this@callbackFlow, eventBuffer)
                }
            }
        } else {
            // Legacy constructor with String path
            @Suppress("DEPRECATION")
            object : FileObserver(path.path, ALL_EVENTS) {
                override fun onEvent(event: Int, path: String?) {
                    handleEvent(event, path, this@callbackFlow, eventBuffer)
                }
            }
        }
        
        mutex.withLock {
            val existing = observers[path]
            if (existing != null) {
                // Reuse existing observer
                existing.referenceCount++
                log(TAG, DEBUG) { "Reusing existing observer for $path (refs: ${existing.referenceCount})" }
            } else {
                // Create new observer
                observer.startWatching()
                observers[path] = WatcherState(observer)
                log(TAG, DEBUG) { "Created new observer for $path" }
            }
        }
        
        // Periodic flush of batched events
        val flushJob = launch {
            while (true) {
                delay(BATCH_DELAY)
                if (eventBuffer.isNotEmpty() && 
                    System.currentTimeMillis() - lastFlushTime > BATCH_DELAY.inWholeMilliseconds) {
                    flushEvents(eventBuffer, this@callbackFlow)
                    lastFlushTime = System.currentTimeMillis()
                }
            }
        }
        
        awaitClose {
            log(TAG, DEBUG) { "Stopping watch for: $path" }
            flushJob.cancel()
            
            // Flush any remaining events
            if (eventBuffer.isNotEmpty()) {
                flushEvents(eventBuffer, this@callbackFlow)
            }
            
            kotlinx.coroutines.runBlocking {
                mutex.withLock {
                    val state = observers[path]
                    if (state != null) {
                        state.referenceCount--
                        if (state.referenceCount <= 0) {
                            state.observer.stopWatching()
                            observers.remove(path)
                            log(TAG, DEBUG) { "Removed observer for $path" }
                        } else {
                            log(TAG, DEBUG) { "Decreased ref count for $path (refs: ${state.referenceCount})" }
                        }
                    }
                }
            }
        }
    }.flowOn(dispatcherProvider.IO)
    
    private fun handleEvent(
        event: Int,
        fileName: String?,
        scope: kotlinx.coroutines.channels.ProducerScope<FileSystemEvent>,
        buffer: MutableList<FileSystemEvent>
    ) {
        val eventType = when (event and FileObserver.ALL_EVENTS) {
            FileObserver.CREATE -> "CREATE"
            FileObserver.DELETE -> "DELETE"
            FileObserver.DELETE_SELF -> "DELETE_SELF"
            FileObserver.MODIFY -> "MODIFY"
            FileObserver.MOVED_FROM -> "MOVED_FROM"
            FileObserver.MOVED_TO -> "MOVED_TO"
            FileObserver.MOVE_SELF -> "MOVE_SELF"
            FileObserver.OPEN -> "OPEN"
            FileObserver.CLOSE_WRITE -> "CLOSE_WRITE"
            FileObserver.CLOSE_NOWRITE -> "CLOSE_NOWRITE"
            FileObserver.ATTRIB -> "ATTRIB"
            FileObserver.ACCESS -> "ACCESS"
            else -> "UNKNOWN($event)"
        }
        
        log(TAG, DEBUG) { "FileObserver event: $eventType for $fileName" }
        
        // Convert to our event types (simplified for now)
        val fsEvent = when (event and FileObserver.ALL_EVENTS) {
            FileObserver.CREATE -> fileName?.let { name ->
                FileSystemEvent.FileCreated(
                    path = LocalPath.build(name)
                )
            }
            FileObserver.DELETE -> fileName?.let { name ->
                FileSystemEvent.FileDeleted(
                    path = LocalPath.build(name)
                )
            }
            FileObserver.MODIFY, FileObserver.CLOSE_WRITE -> fileName?.let { name ->
                FileSystemEvent.FileModified(
                    path = LocalPath.build(name)
                )
            }
            FileObserver.ATTRIB -> fileName?.let { name ->
                FileSystemEvent.DirectoryChanged(
                    path = LocalPath.build(name),
                    changeType = FileSystemEvent.DirectoryChanged.ChangeType.PERMISSIONS_CHANGED
                )
            }
            else -> null
        }
        
        fsEvent?.let { 
            buffer.add(it)
            
            // Flush if buffer is getting large
            if (buffer.size >= MAX_BATCH_SIZE) {
                flushEvents(buffer, scope)
            }
        }
    }
    
    private fun flushEvents(
        buffer: MutableList<FileSystemEvent>,
        scope: kotlinx.coroutines.channels.ProducerScope<FileSystemEvent>
    ) {
        if (buffer.isEmpty()) return
        
        log(TAG, DEBUG) { "Flushing ${buffer.size} events" }
        
        // Check for massive changes
        if (buffer.size > MASSIVE_CHANGE_THRESHOLD) {
            val first = buffer.first().path
            val path = when (first) {
                is LocalPath -> first.parent() ?: first
                else -> first
            }
            scope.trySend(FileSystemEvent.MassiveChange(
                path = path,
                changeCount = buffer.size
            ))
            buffer.clear()
            return
        }
        
        // Send individual events
        buffer.forEach { event ->
            scope.trySend(event)
        }
        buffer.clear()
    }
    
    override suspend fun startWatching(path: APath) {
        mutex.withLock {
            if (observers.containsKey(path)) {
                log(TAG, DEBUG) { "Already watching: $path" }
                return
            }
            
            if (path !is LocalPath) {
                log(TAG, WARN) { "Cannot watch non-local path: $path" }
                return
            }
            
            val file = File(path.path)
            if (!file.exists()) {
                log(TAG, WARN) { "Path does not exist: $path" }
                return
            }
            
            val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(file, ALL_EVENTS) {
                    override fun onEvent(event: Int, path: String?) {
                        // No-op for non-flow watching
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(path.path, ALL_EVENTS) {
                    override fun onEvent(event: Int, path: String?) {
                        // No-op for non-flow watching
                    }
                }
            }
            
            observer.startWatching()
            observers[path] = WatcherState(observer)
            log(TAG, DEBUG) { "Started watching: $path" }
        }
    }
    
    override suspend fun stopWatching(path: APath) {
        mutex.withLock {
            val state = observers.remove(path)
            state?.observer?.stopWatching()
            log(TAG, DEBUG) { "Stopped watching: $path" }
        }
    }
    
    override suspend fun stopAll() {
        mutex.withLock {
            observers.values.forEach { it.observer.stopWatching() }
            val count = observers.size
            observers.clear()
            log(TAG, DEBUG) { "Stopped all $count watchers" }
        }
    }
    
    override fun getActiveWatcherCount(): Int = observers.size
    
    companion object {
        private val TAG = logTag("Explorer", "FileSystemWatcher")
        private val BATCH_DELAY = 100.milliseconds
        private const val MAX_BATCH_SIZE = 50
        private const val MASSIVE_CHANGE_THRESHOLD = 100
    }
}
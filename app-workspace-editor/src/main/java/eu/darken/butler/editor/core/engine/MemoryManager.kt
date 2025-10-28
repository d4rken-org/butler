package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryManager @Inject constructor() {
    
    private val tag = logTag("MemoryManager")
    
    private val chunkAccessOrder = mutableListOf<TextChunk.ChunkId>()
    private val chunkSizes = ConcurrentHashMap<TextChunk.ChunkId, Long>()
    private val dirtyChunks = mutableSetOf<TextChunk.ChunkId>()
    private val accessMutex = Mutex()
    
    var maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES
        private set
    
    var currentMemoryUsage: Long = 0L
        private set
    
    suspend fun addChunk(chunk: TextChunk) = accessMutex.withLock {
        val chunkSize = chunk.content.toByteArray().size.toLong()
        
        // If chunk already exists, update access order
        if (chunkSizes.containsKey(chunk.id)) {
            updateAccessOrder(chunk.id)
            return@withLock
        }
        
        // Add new chunk
        chunkSizes[chunk.id] = chunkSize
        chunkAccessOrder.add(chunk.id)
        currentMemoryUsage += chunkSize
        
        if (chunk.isDirty) {
            dirtyChunks.add(chunk.id)
        }
        
        log(tag) { "Added chunk ${chunk.id}: ${chunkSize} bytes (total: ${currentMemoryUsage} bytes)" }
        
        // Trigger eviction if necessary
        evictIfNecessary()
    }
    
    suspend fun removeChunk(chunkId: TextChunk.ChunkId) = accessMutex.withLock {
        val chunkSize = chunkSizes.remove(chunkId) ?: return@withLock
        
        chunkAccessOrder.remove(chunkId)
        dirtyChunks.remove(chunkId)
        currentMemoryUsage -= chunkSize
        
        log(tag) { "Removed chunk $chunkId: ${chunkSize} bytes (total: ${currentMemoryUsage} bytes)" }
    }
    
    suspend fun markChunkDirty(chunkId: TextChunk.ChunkId) = accessMutex.withLock {
        dirtyChunks.add(chunkId)
        updateAccessOrder(chunkId)
        log(tag) { "Marked chunk $chunkId as dirty" }
    }
    
    suspend fun markChunkClean(chunkId: TextChunk.ChunkId) = accessMutex.withLock {
        dirtyChunks.remove(chunkId)
        log(tag) { "Marked chunk $chunkId as clean" }
    }
    
    suspend fun updateAccessOrder(chunkId: TextChunk.ChunkId) = accessMutex.withLock {
        // Move to end (most recently used)
        chunkAccessOrder.remove(chunkId)
        chunkAccessOrder.add(chunkId)
    }
    
    suspend fun getEvictionCandidates(bytesNeeded: Long): List<TextChunk.ChunkId> = accessMutex.withLock {
        val candidates = mutableListOf<TextChunk.ChunkId>()
        var freedBytes = 0L
        
        // LRU eviction - start from least recently used
        for (chunkId in chunkAccessOrder) {
            // Don't evict dirty chunks
            if (dirtyChunks.contains(chunkId)) {
                continue
            }
            
            val chunkSize = chunkSizes[chunkId] ?: continue
            candidates.add(chunkId)
            freedBytes += chunkSize
            
            if (freedBytes >= bytesNeeded) {
                break
            }
        }
        
        log(tag) { "Found ${candidates.size} eviction candidates that would free $freedBytes bytes" }
        return@withLock candidates
    }
    
    suspend fun getDirtyChunks(): List<TextChunk.ChunkId> = accessMutex.withLock {
        dirtyChunks.toList()
    }
    
    suspend fun getMemoryPressure(): MemoryPressure {
        val usageRatio = currentMemoryUsage.toDouble() / maxMemoryBytes.toDouble()
        
        return when {
            usageRatio < 0.7 -> MemoryPressure.LOW
            usageRatio < 0.85 -> MemoryPressure.MEDIUM
            usageRatio < 0.95 -> MemoryPressure.HIGH
            else -> MemoryPressure.CRITICAL
        }
    }
    
    suspend fun clear() = accessMutex.withLock {
        log(tag) { "Clearing memory manager" }
        chunkAccessOrder.clear()
        chunkSizes.clear()
        dirtyChunks.clear()
        currentMemoryUsage = 0L
    }
    
    fun updateMaxMemory(newMaxMemoryBytes: Long) {
        maxMemoryBytes = newMaxMemoryBytes.coerceAtLeast(MIN_MEMORY_BYTES)
        log(tag) { "Updated max memory to: $maxMemoryBytes bytes" }
    }
    
    suspend fun getMemoryStats(): MemoryStats = accessMutex.withLock {
        MemoryStats(
            currentUsage = currentMemoryUsage,
            maxMemory = maxMemoryBytes,
            totalChunks = chunkSizes.size,
            dirtyChunks = dirtyChunks.size,
            usagePercentage = (currentMemoryUsage.toDouble() / maxMemoryBytes.toDouble() * 100).toInt()
        )
    }
    
    private suspend fun evictIfNecessary() {
        val pressure = getMemoryPressure()
        
        when (pressure) {
            MemoryPressure.HIGH -> {
                // Try to free 20% of memory
                val targetBytes = (maxMemoryBytes * 0.2).toLong()
                triggerEviction(targetBytes)
            }
            MemoryPressure.CRITICAL -> {
                // Try to free 30% of memory
                val targetBytes = (maxMemoryBytes * 0.3).toLong()
                triggerEviction(targetBytes)
            }
            else -> {
                // No action needed
            }
        }
    }
    
    private suspend fun triggerEviction(bytesNeeded: Long) {
        val candidates = getEvictionCandidates(bytesNeeded)
        
        if (candidates.isNotEmpty()) {
            log(tag) { "Memory pressure detected, evicting ${candidates.size} chunks" }
            
            // Note: Actual eviction would be handled by ChunkManager
            // This just identifies candidates
        }
    }
    
    companion object {
        private const val TAG = "MemoryManager"
        
        const val DEFAULT_MAX_MEMORY_BYTES = 50 * 1024 * 1024L // 50MB
        const val MIN_MEMORY_BYTES = 10 * 1024 * 1024L // 10MB
    }
}

enum class MemoryPressure {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class MemoryStats(
    val currentUsage: Long,
    val maxMemory: Long,
    val totalChunks: Int,
    val dirtyChunks: Int,
    val usagePercentage: Int
) {
    val availableMemory: Long get() = maxMemory - currentUsage
    val isUnderPressure: Boolean get() = usagePercentage > 70
}
package eu.darken.butler.debug.core

import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugLogRepo @Inject constructor() {

    private val mutex = Mutex()
    private val bufferedLogger = BufferedLogger()
    private var refCount = 0

    val logLines: Flow<List<String>> = bufferedLogger.logLines.map { lines ->
        lines.map { it.format() }
    }

    val currentLogLines: List<String>
        get() = bufferedLogger.logLines.value.map { it.format() }

    suspend fun install() = mutex.withLock {
        refCount++
        if (refCount == 1) {
            log(TAG) { "Installing BufferedLogger" }
            Logging.install(bufferedLogger)
        } else {
            log(TAG) { "BufferedLogger already installed, refCount=$refCount" }
        }
    }

    suspend fun uninstall() = mutex.withLock {
        if (refCount <= 0) {
            log(TAG, WARN) { "uninstall() called but refCount=$refCount" }
            return@withLock
        }
        refCount--
        if (refCount == 0) {
            log(TAG) { "Uninstalling BufferedLogger" }
            Logging.remove(bufferedLogger)
        } else {
            log(TAG) { "BufferedLogger still in use, refCount=$refCount" }
        }
    }

    fun clear() {
        bufferedLogger.clear()
    }

    companion object {
        private val TAG = logTag("Debug", "LogRepo")
    }
}

package eu.darken.butler.common.serialization

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.EOFException
import java.io.File
import java.io.IOException

abstract class SerializedStorage<T>(
    private val dispatcherProvider: DispatcherProvider,
    private val logTag: String,
) {
    abstract val provideBackupPath: () -> File
    abstract val provideBackupFileName: () -> String

    private val saveCurrent by lazy {
        File(provideBackupPath(), "${provideBackupFileName()}.json").also {
            it.parentFile!!.mkdirs()
        }
    }
    private val saveBackup by lazy {
        File(provideBackupPath(), "${provideBackupFileName()}.json.backup").also {
            it.parentFile!!.mkdirs()
        }
    }

    abstract val provideSerializer: () -> KSerializer<T>
    abstract val provideJson: () -> Json
    private val serializer by lazy { provideSerializer() }
    private val json by lazy { provideJson() }

    private val lock = Mutex()

    suspend fun save(data: T): Unit = lock.withLock {
        log(logTag) { "save(): $data" }
        withContext(NonCancellable + dispatcherProvider.IO) {
            if (saveCurrent.exists()) {
                saveCurrent.copyTo(saveBackup, overwrite = true)
            }
            try {
                val rawJson = json.encodeToString(serializer, data)
                saveCurrent.writeText(rawJson)
            } catch (e: IOException) {
                log(logTag, ERROR) { "Saving failed: ${e.asLog()}" }
                saveBackup.copyTo(saveCurrent, overwrite = true)
                saveBackup.delete()
            }
        }
    }

    suspend fun load(): T? = lock.withLock {
        var data: T? = null
        withContext(dispatcherProvider.IO) {
            if (!saveCurrent.exists()) return@withContext
            try {
                val rawJson = saveCurrent.readText()
                data = json.decodeFromString(serializer, rawJson)
            } catch (e: EOFException) {
                log(logTag, ERROR) { "Empty data file: $saveCurrent" }
                saveCurrent.delete()
            }
        }
        log(logTag) { "load(): $data" }
        return data
    }
}
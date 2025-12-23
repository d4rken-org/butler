package eu.darken.butler.common.files.local.service

import androidx.annotation.Keep
import dagger.Lazy
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.local.ipc.FileOpsConnection
import eu.darken.butler.common.files.local.ipc.FileOpsHost
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Keep
class LocalServiceHost @Inject constructor(
    private val fileOpsHost: Lazy<FileOpsHost>,
) : LocalServiceConnection.Stub() {

    override fun getFileOps(): FileOpsConnection {
        log(TAG) { "getFileOps()" }
        return fileOpsHost.get()
    }

    companion object {
        private val TAG = logTag("Local", "Service", "Host")
    }
}

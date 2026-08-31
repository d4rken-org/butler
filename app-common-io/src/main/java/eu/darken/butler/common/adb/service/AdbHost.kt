package eu.darken.butler.common.adb.service

import android.content.Context
import android.os.IBinder
import androidx.annotation.Keep
import dagger.Lazy
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.adb.service.internal.BaseAdbHost
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.HostRecorderLog
import eu.darken.butler.common.debug.bugreport.BugReportStorage
import eu.darken.butler.common.debug.logging.LogCatLogger
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.sharedresource.HasSharedResource
import eu.darken.butler.common.sharedresource.Resource
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.shell.SharedShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@Keep
class AdbHost(
    context: Context
) : BaseAdbHost(TAG, context), HasSharedResource<Any> {

    override val sharedResource = SharedResource.createKeepAlive(TAG, hostScope)

    private lateinit var component: AdbComponent
    private lateinit var keepAliveToken: Resource<*>

    @Inject lateinit var sharedShell: SharedShell
    @Inject lateinit var serviceHost: Lazy<AdbServiceHost>

    private val logCatLogger = LogCatLogger()
    private val currentOptions = MutableStateFlow(AdbHostOptions())

    init {
        Bugs.processTag = "ADB"
        if (BuildConfigWrap.DEBUG) {
            Logging.install(logCatLogger)
            log(TAG) { "BuildConfigWrap.DEBUG=true" }
        }
        log(TAG, INFO) { "init()" }

        runBlocking { onStart() }
    }

    suspend fun onStart() {
        component = DaggerAdbComponent.builder().application(context).build().also {
            it.inject(this)
        }

        keepAliveToken = sharedResource.get()

        val recorderLog = HostRecorderLog(BugReportStorage.ADB_LOG_FILE, TAG)

        currentOptions
            .onEach { options ->
                log(TAG) { "New options: $options" }
                if (options.isDebug && Logging.loggers.none { it == logCatLogger }) {
                    Logging.install(logCatLogger)
                    log(TAG) { "Logger installed!" }
                } else if (!options.isDebug) {
                    log(TAG) { "Logger will be removed now!" }
                    Logging.remove(logCatLogger)
                }

                recorderLog.update(options.recorderPath)

                Bugs.isDebug = options.isDebug
                Bugs.isTrace = options.isTrace
            }
            .launchIn(hostScope)
    }

    override suspend fun onDestroy() {
        keepAliveToken.close()
    }

    override fun getUserConnection(): IBinder = serviceHost.get().also {
        // Shizuku has no init arguments, so the identity rides along with the initial options push,
        // which the client's handshake does before asking for this connection. Only the first stamp
        // sticks, so a newer client binding to this (possibly stale) host cannot overwrite it.
        it.identityStamp.stamp(currentOptions.value.hostIdentity)
    }

    override fun updateHostOptions(options: AdbHostOptions) {
        log(TAG) { "updateHostOptions(): $options" }
        currentOptions.value = options
    }

    companion object {
        internal val TAG = logTag("ADB", "Host")
    }
}
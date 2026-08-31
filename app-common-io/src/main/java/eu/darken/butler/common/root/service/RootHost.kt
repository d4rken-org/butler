package eu.darken.butler.common.root.service

import android.annotation.SuppressLint
import android.util.Log
import androidx.annotation.Keep
import dagger.Lazy
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.HostRecorderLog
import eu.darken.butler.common.debug.bugreport.BugReportStorage
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.root.service.internal.BaseRootHost
import eu.darken.butler.common.root.service.internal.RootIPC
import eu.darken.butler.common.sharedresource.HasSharedResource
import eu.darken.butler.common.sharedresource.Resource
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.sharedresource.adoptChildResource
import eu.darken.butler.common.shell.SharedShell
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.TimeoutException
import javax.inject.Inject


/**
 * This class' main method will be launched as root. You can access any other class from your
 * package, but not instances - this is a separate process from the UI.
 */
@Keep
@SuppressLint("UnsafeDynamicallyLoadedCode")
class RootHost(_args: List<String>) : HasSharedResource<Any>, BaseRootHost("$TAG#${hashCode()}", _args) {

    override val sharedResource = SharedResource.createKeepAlive(iTag, hostScope)

    lateinit var component: RootComponent

    @Inject lateinit var sharedShell: SharedShell
    @Inject lateinit var serviceHost: Lazy<RootServiceHost>
    @Inject lateinit var rootIpcFactory: RootIPC.Factory

    override suspend fun onInit() {
        component = DaggerRootComponent.builder().application(systemContext).build().also {
            it.inject(this)
        }
    }

    override suspend fun onExecute() {
        log(iTag) { "Starting IPC connection via $rootIpcFactory" }
        // Stamped from our launch args, never read from this process: the Dagger graph above is built
        // on the system context, which identifies the "android" package rather than ours.
        val userBinder = serviceHost.get().also { it.identityStamp.stamp(initOptions.hostIdentity) }
        val ipc = rootIpcFactory.create(
            initArgs = initOptions,
            userProvidedBinder = userBinder,
        )
        log(iTag) { "IPC created: $ipc" }

        val recorderLog = HostRecorderLog(BugReportStorage.ROOT_LOG_FILE, TAG)

        ipc.hostOptions
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

        val keepAliveToken: Resource<*> = sharedResource.get()

        log(iTag) { "Launching SharedShell with root" }
        adoptChildResource(sharedShell)

        try {
            log(iTag) { "Ready, now broadcasting..." }
            ipc.broadcastAndWait()
        } catch (e: TimeoutException) {
            log(iTag, ERROR) { "Non-root process did not connect in a timely fashion" }
        }

        keepAliveToken.close()
    }

    @Keep
    companion object {
        internal val TAG = logTag("Root", "Host")

        @Keep
        @JvmStatic
        fun main(args: Array<String>) {
            Bugs.processTag = "Root"
            Log.v(TAG, "main(args=$args)")
            RootHost(args.toList()).start()
        }
    }
}

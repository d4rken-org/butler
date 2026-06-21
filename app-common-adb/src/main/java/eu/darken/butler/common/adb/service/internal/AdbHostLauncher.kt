package eu.darken.butler.common.adb.service.internal

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import dagger.Reusable
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.adb.AdbException
import eu.darken.butler.common.adb.service.AdbHostOptions
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ipc.getInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.*
import javax.inject.Inject
import kotlin.reflect.KClass

@Reusable
class AdbHostLauncher @Inject constructor() {

    fun <Service : IInterface, Host : AdbConnection> createConnection(
        serviceClass: KClass<Service>,
        hostClass: KClass<Host>,
        options: AdbHostOptions,
    ): Flow<ConnectionWrapper<Service, Host>> = callbackFlow {
        if (Shizuku.getVersion() < 10) throw IllegalStateException("Shizuku API10+ required")

        val serviceArgs = UserServiceArgs(
            ComponentName(
                BuildConfigWrap.APPLICATION_ID,
                hostClass.qualifiedName!!
            )
        ).apply {
            daemon(false)
            processNameSuffix(logTag("ADB"))
            debuggable(options.isDebug)
            version(BuildConfigWrap.VERSION_CODE.toInt())
        }

        val awaitDisconnect = CompletableDeferred<Unit>()
        // Set before we intentionally unbind so our own disconnect doesn't trip the unexpected-disconnect path.
        val closing = AtomicBoolean(false)

        val callback: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName, binder: IBinder?) {
                log(TAG) { "onServiceConnected($componentName,$binder)" }

                if (binder?.pingBinder() != true) {
                    log(TAG) { "onServiceConnected(...) Invalid binder (ping failed)" }
                    return
                }

                val baseConnection = try {
                    AdbConnection.Stub.asInterface(binder)!!
                } catch (e: Exception) {
                    close(AdbException("Failed to get base connection", e))
                    return
                }

                // Initial options, Shizuku has no init arguments through which these can be supplied earlier
                log(TAG) { "Updating host options to $options" }
                baseConnection.updateHostOptions(options)

                val userConnection = try {
                    baseConnection.userConnection.getInterface(serviceClass) as Service
                } catch (e: Exception) {
                    close(AdbException("Failed to get user connection (ADB)", e))
                    return
                }

                log(TAG) { "onServiceConnected(...) -> $userConnection" }
                @Suppress("UNCHECKED_CAST")
                trySendBlocking(ConnectionWrapper(userConnection, baseConnection as Host))
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                log(TAG) { "onServiceDisconnected($componentName)" }
                awaitDisconnect.complete(Unit)
                // If Shizuku drops the service while we still want it, fail the flow so the
                // SharedResource stops vending a dead binder during its keep-alive window.
                if (!closing.get()) {
                    close(AdbException("Shizuku user service disconnected unexpectedly"))
                }
            }
        }

        // try/finally so cleanup runs even when cancelled while parked in awaitClose; the previous
        // awaitClose {} cleanup didn't run on producer cancellation, leaking the Shizuku binding.
        var bound = false
        try {
            try {
                Shizuku.bindUserService(serviceArgs, callback)
                bound = true
            } catch (e: Exception) {
                throw AdbException("Failed to bind Shizuku user service", e)
            }

            log(TAG) { "Waiting for flow to close" }
            awaitClose { log(TAG) { "awaitClose() reached, flow is closing…" } }
        } finally {
            closing.set(true)
            if (bound) {
                withContext(NonCancellable) {
                    log(TAG) { "Unbinding Shizuku user service…" }
                    runCatching { Shizuku.unbindUserService(serviceArgs, callback, true) }
                        .onFailure { log(TAG, WARN) { "unbindUserService() failed: ${it.asLog()}" } }
                    // Wait for the actual disconnect; quick flow restarts otherwise cause
                    // DeadObjectExceptions from a not-yet-released Shizuku binder.
                    withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { awaitDisconnect.await() }
                    log(TAG) { "Shizuku user service unbound." }
                }
            }
        }
    }

    data class ConnectionWrapper<Service : IInterface, Host : AdbConnection>(
        val service: Service,
        val host: Host,
    )

    companion object {
        private val TAG = logTag("ADB", "Host", "Launcher")

        // Bounded wait for the service to actually disconnect after we unbind.
        private const val DISCONNECT_TIMEOUT_MS = 500L
    }
}
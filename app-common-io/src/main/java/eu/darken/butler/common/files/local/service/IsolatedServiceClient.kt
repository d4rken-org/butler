package eu.darken.butler.common.files.local.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.ipc.IpcClientModule
import eu.darken.butler.common.sharedresource.SharedResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IsolatedServiceClient @Inject constructor(
    @AppScope coroutineScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val fileOpsClientFactory: FileOpsClient.Factory,
) : SharedResource<IsolatedServiceClient.Connection>(
    tag = TAG,
    parentScope = coroutineScope,
    source = callbackFlow {
        log(TAG) { "Binding to IsolatedService..." }

        val currentBinder = AtomicReference<IBinder?>(null)

        val deathRecipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                log(TAG, ERROR) { "binderDied() - Service process killed (storage disconnected?)" }
                currentBinder.getAndSet(null)?.unlinkToDeath(this, 0)
                close(ServiceProcessDiedException("Isolated service process was killed"))
            }
        }

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                log(TAG) { "onServiceConnected($name)" }
                try {
                    currentBinder.set(service)
                    service?.linkToDeath(deathRecipient, 0)

                    val serviceConnection = IsolatedServiceConnection.Stub.asInterface(service)
                    val fileOpsConnection = serviceConnection.fileOps

                    trySend(fileOpsConnection)
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to setup service connection: ${e.asLog()}" }
                    close(e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                log(TAG, WARN) { "onServiceDisconnected($name)" }
                currentBinder.getAndSet(null)?.unlinkToDeath(deathRecipient, 0)
            }

            override fun onBindingDied(name: ComponentName?) {
                log(TAG, ERROR) { "onBindingDied($name) - Service process died" }
                currentBinder.getAndSet(null)?.unlinkToDeath(deathRecipient, 0)
                close(ServiceProcessDiedException("Isolated service binding died"))
            }

            override fun onNullBinding(name: ComponentName?) {
                log(TAG, ERROR) { "onNullBinding($name)" }
                close(IllegalStateException("IsolatedService returned null binding"))
            }
        }

        val intent = Intent(context, IsolatedService::class.java)
        val bound = context.bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )

        if (!bound) {
            log(TAG, ERROR) { "Failed to bind to IsolatedService" }
            close(ServiceBindException("Failed to bind to IsolatedService"))
        } else {
            log(TAG) { "Bind request sent successfully" }
        }

        awaitClose {
            log(TAG) { "Unbinding from IsolatedService" }
            currentBinder.getAndSet(null)?.unlinkToDeath(deathRecipient, 0)
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                log(TAG, WARN) { "Error unbinding: ${e.asLog()}" }
            }
        }
    }
        .onStart { log(TAG) { "Starting service connection" } }
        .catch { e ->
            log(TAG, ERROR) { "Service connection failed: ${e.asLog()}" }
            throw when (e) {
                is ServiceProcessDiedException -> e
                is ServiceBindException -> e
                is DeadObjectException -> ServiceProcessDiedException("Service died", e)
                else -> ServiceBindException("Connection error", e)
            }
        }
        .onCompletion { log(TAG) { "Service connection completed" } }
        .map { fileOpsConnection ->
            Connection(
                fileOpsClient = fileOpsClientFactory.create(fileOpsConnection),
            )
        }
) {

    data class Connection(
        val fileOpsClient: FileOpsClient,
    ) {
        val clientModules: List<IpcClientModule> = listOf(fileOpsClient)
    }

    class ServiceProcessDiedException(
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    class ServiceBindException(
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    companion object {
        internal val TAG = logTag("Isolated", "Service", "Client")
    }
}

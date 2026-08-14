package eu.darken.butler.common.adb.service.internal

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.adb.AdbException
import eu.darken.butler.common.adb.service.AdbHostOptions
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ipc.getInterface
import kotlinx.coroutines.CompletableDeferred
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import kotlin.reflect.KClass

/**
 * Seam that lets [AdbHostLauncher.createConnection] be unit-tested without Shizuku.
 *
 * The launcher keeps the bind/unbind ordering, the `bound` guard, and the bounded await-disconnect
 * (the teardown logic under test). This collaborator is the only thing touching the Shizuku statics
 * ([Shizuku.getVersion]/[Shizuku.bindUserService]/[Shizuku.unbindUserService]) and the Android
 * [ServiceConnection]. Tests replace it via the launcher's primary constructor; the real
 * implementation below is exercised end-to-end on real devices.
 */
interface ShizukuUserService {
    fun bind()

    fun unbind()

    /** Suspends until the service actually disconnects (onServiceDisconnected). */
    suspend fun awaitDisconnect()
}

interface ShizukuUserServiceFactory {
    fun apiVersion(): Int

    fun <Host : AdbConnection> create(
        hostClass: KClass<Host>,
        options: AdbHostOptions,
        onConnected: (IBinder?) -> Unit,
        onDisconnected: () -> Unit,
    ): ShizukuUserService

    /**
     * Post-connect handshake: validate the binder, wrap it, push the initial host options and resolve
     * our user interface. Every failure is thrown to the caller (the launcher decides what to do with
     * it), nothing is swallowed here. All of this does binder transactions, so the caller must not run
     * it on Shizuku's main-thread callback.
     */
    fun <Service : IInterface, Host : AdbConnection> handshake(
        binder: IBinder?,
        serviceClass: KClass<Service>,
        options: AdbHostOptions,
    ): Pair<Service, Host>
}

internal class DefaultShizukuUserServiceFactory : ShizukuUserServiceFactory {

    override fun apiVersion(): Int = Shizuku.getVersion()

    override fun <Host : AdbConnection> create(
        hostClass: KClass<Host>,
        options: AdbHostOptions,
        onConnected: (IBinder?) -> Unit,
        onDisconnected: () -> Unit,
    ): ShizukuUserService {
        val serviceArgs = UserServiceArgs(
            ComponentName(BuildConfigWrap.APPLICATION_ID, hostClass.qualifiedName!!)
        ).apply {
            daemon(false)
            processNameSuffix(logTag("ADB"))
            debuggable(options.isDebug)
            version(BuildConfigWrap.VERSION_CODE.toInt())
        }

        val disconnected = CompletableDeferred<Unit>()
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = onConnected(binder)
            override fun onServiceDisconnected(name: ComponentName?) {
                disconnected.complete(Unit)
                onDisconnected()
            }
        }

        return object : ShizukuUserService {
            override fun bind() = Shizuku.bindUserService(serviceArgs, callback)
            override fun unbind() = Shizuku.unbindUserService(serviceArgs, callback, true)
            override suspend fun awaitDisconnect() {
                disconnected.await()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <Service : IInterface, Host : AdbConnection> handshake(
        binder: IBinder?,
        serviceClass: KClass<Service>,
        options: AdbHostOptions,
    ): Pair<Service, Host> {
        if (binder?.pingBinder() != true) throw AdbException("Invalid binder (ping failed)")

        val baseConnection = AdbConnection.Stub.asInterface(binder)
            ?: throw AdbException("Failed to get base connection")

        // Initial options, Shizuku has no init arguments through which these can be supplied earlier
        baseConnection.updateHostOptions(options)

        val userConnection = baseConnection.userConnection.getInterface(serviceClass) as Service

        return userConnection to (baseConnection as Host)
    }
}

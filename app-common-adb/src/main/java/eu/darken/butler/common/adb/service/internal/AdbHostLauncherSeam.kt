package eu.darken.butler.common.adb.service.internal

import android.content.ComponentName
import android.os.IBinder
import android.os.IInterface
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.adb.AdbException
import eu.darken.butler.common.adb.service.AdbHostOptions
import eu.darken.butler.common.adb.shizuku.AdbServerConnection
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ipc.getInterface
import eu.darken.porter.sdk.UserServiceArgs
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * Seam that lets [AdbHostLauncher.createConnection] be unit-tested without the Porter SDK.
 *
 * The launcher keeps the collection, the stop ordering and the bounded waits (the teardown logic under
 * test). This collaborator is the only thing touching the SDK's user service calls. Tests replace it
 * via the launcher's primary constructor; the real implementation below is exercised end-to-end on
 * real devices.
 */
interface AdbUserService {
    /**
     * Cold. Collecting binds the service and emits its binder once it connected. Completes when the
     * service dies, or when the server connection it was created on is replaced or lost.
     */
    fun binders(): Flow<IBinder>

    /** Asks the server to stop the service. A service that stops ends the [binders] collection. */
    suspend fun stop()
}

interface AdbUserServiceFactory {
    /** The user service of [hostClass] on the current server connection, null when there is none. */
    fun <Host : AdbConnection> create(
        hostClass: KClass<Host>,
        options: AdbHostOptions,
    ): AdbUserService?

    /**
     * Post-connect handshake: validate the binder, push the initial host options and resolve our user
     * interface. Every failure is thrown to the caller (the launcher decides what to do with it),
     * nothing is swallowed here. All of this does binder transactions, so the caller must not run it
     * on the thread collecting [AdbUserService.binders].
     */
    fun <Service : IInterface, Host : AdbConnection> handshake(
        binder: IBinder,
        serviceClass: KClass<Service>,
        options: AdbHostOptions,
    ): Pair<Service, Host>
}

/**
 * Identical for bind and stop: the server keys a stop on these, and [UserServiceArgs.tag] is what
 * identifies the service once R8 renamed its class.
 */
internal fun adbHostServiceArgs(
    componentName: ComponentName,
    options: AdbHostOptions,
    versionCode: Int,
): UserServiceArgs = UserServiceArgs(
    componentName = componentName,
    processNameSuffix = logTag("ADB"),
    tag = ADB_HOST_SERVICE_TAG,
    version = versionCode,
    debuggable = options.isDebug,
    daemon = false,
)

internal const val ADB_HOST_SERVICE_TAG = "butler-adb-host"

internal class DefaultAdbUserServiceFactory(
    private val currentServer: () -> AdbServerConnection?,
    private val serviceArgs: (KClass<out AdbConnection>, AdbHostOptions) -> UserServiceArgs = { hostClass, options ->
        adbHostServiceArgs(
            componentName = ComponentName(BuildConfigWrap.APPLICATION_ID, hostClass.qualifiedName!!),
            options = options,
            versionCode = BuildConfigWrap.VERSION_CODE.toInt(),
        )
    },
) : AdbUserServiceFactory {

    override fun <Host : AdbConnection> create(
        hostClass: KClass<Host>,
        options: AdbHostOptions,
    ): AdbUserService? {
        // Pinned to this connection: its user service flow completes once the connection is replaced,
        // which ends the generation, and the next one is created on the replacement.
        val server = currentServer() ?: return null
        val args = serviceArgs(hostClass, options)
        return object : AdbUserService {
            override fun binders(): Flow<IBinder> = server.userService(args)
            override suspend fun stop() = server.stopUserService(args)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <Service : IInterface, Host : AdbConnection> handshake(
        binder: IBinder,
        serviceClass: KClass<Service>,
        options: AdbHostOptions,
    ): Pair<Service, Host> {
        if (!binder.pingBinder()) throw AdbException("Invalid binder (ping failed)")

        val baseConnection = AdbConnection.Stub.asInterface(binder)
            ?: throw AdbException("Failed to get base connection")

        // Initial options, user services have no init arguments through which these can be supplied earlier
        baseConnection.updateHostOptions(options)

        val userConnection = baseConnection.userConnection.getInterface(serviceClass) as Service

        return userConnection to (baseConnection as Host)
    }
}

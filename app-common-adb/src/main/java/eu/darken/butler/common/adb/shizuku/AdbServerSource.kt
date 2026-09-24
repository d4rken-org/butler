package eu.darken.butler.common.adb.shizuku

import android.os.IBinder
import eu.darken.porter.sdk.UserServiceArgs
import kotlinx.coroutines.flow.Flow

/**
 * Where [ShizukuWrapper] and the host launcher get their connection from. The production
 * implementation is [PorterServerSource]; tests replace it, because the SDK's connection and
 * availability types cannot be constructed outside the SDK.
 */
internal interface AdbServerSource {

    /** Null while no server is connected, and a new connection for every server that replaces one. */
    val connection: Flow<AdbServerConnection?>

    fun current(): AdbServerConnection?

    suspend fun availability(): AdbAvailability
}

/** One connection to one server. Equal only to itself. */
internal interface AdbServerConnection {

    val backend: AdbBackend

    /** The latest permission state the server reported. */
    val permission: Flow<AdbPermissionState>

    suspend fun checkPermission(): AdbPermissionState

    /** Suspends until the user answered, and throws if the connection is lost before that. */
    suspend fun requestPermission(): AdbPermissionState

    /**
     * Cold. Collecting binds the service and emits its binder; completes when the service dies or this
     * connection is replaced or lost.
     */
    fun userService(args: UserServiceArgs): Flow<IBinder>

    /** Sends the service its destroy transaction. */
    suspend fun stopUserService(args: UserServiceArgs)
}

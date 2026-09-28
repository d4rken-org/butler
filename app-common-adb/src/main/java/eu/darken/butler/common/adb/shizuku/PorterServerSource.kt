package eu.darken.butler.common.adb.shizuku

import android.content.Context
import android.os.IBinder
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterAvailability
import eu.darken.porter.sdk.PorterBackend
import eu.darken.porter.sdk.PorterConnection
import eu.darken.porter.sdk.UserServiceArgs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class PorterServerSource(private val context: Context) : AdbServerSource {

    override val connection: Flow<AdbServerConnection?> = Porter.connection.map { it?.let(::PorterServerConnection) }

    override fun current(): AdbServerConnection? = Porter.connection.value?.let(::PorterServerConnection)

    override suspend fun availability(): AdbAvailability = when (val availability = Porter.availability(context)) {
        is PorterAvailability.NotInstalled -> AdbAvailability.NotInstalled
        is PorterAvailability.InstalledUnrecognized -> AdbAvailability.InstalledUnrecognized(
            backend = availability.backend.toAdb(),
            packageName = availability.packageName,
        )

        is PorterAvailability.InstalledNotConnected -> AdbAvailability.InstalledNotConnected(
            backend = availability.backend.toAdb(),
            packageName = availability.packageName,
        )

        is PorterAvailability.Incompatible -> AdbAvailability.Incompatible(
            backend = availability.incompatibility.backend.toAdb(),
            packageName = availability.packageName,
            serverTooOld = availability.incompatibility.serverTooOld,
            clientTooOld = availability.incompatibility.clientTooOld,
        )

        is PorterAvailability.Connected -> AdbAvailability.Connected(
            backend = availability.backend.toAdb(),
            packageName = availability.packageName,
        )
    }
}

/** Equality is the SDK connection's, which is identity. */
private data class PorterServerConnection(private val sdk: PorterConnection) : AdbServerConnection {

    override val backend: AdbBackend = sdk.backend.toAdb()

    override val permission: Flow<AdbPermissionState> = sdk.permission.map { it.toAdb() }

    override suspend fun checkPermission(): AdbPermissionState = sdk.checkPermission().toAdb()

    override suspend fun requestPermission(): AdbPermissionState = sdk.requestPermission().toAdb()

    override fun userService(args: UserServiceArgs): Flow<IBinder> = sdk.userService(args)

    override suspend fun stopUserService(args: UserServiceArgs) = sdk.stopUserService(args)

    override fun toString(): String = "PorterServerConnection($sdk)"
}

private fun PorterBackend.toAdb(): AdbBackend = when (this) {
    PorterBackend.PORTER -> AdbBackend.PORTER
    PorterBackend.SHIZUKU -> AdbBackend.SHIZUKU
}

private fun PermissionState.toAdb(): AdbPermissionState = when (this) {
    is PermissionState.Granted -> AdbPermissionState.Granted
    is PermissionState.Denied -> AdbPermissionState.Denied(permanentlyDenied = permanentlyDenied)
}

package eu.darken.butler.setup.ui.items

import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule

/** What the ADB access setup card tells the user, resolved once for every surface that renders it. */
enum class AdbCardStatus {
    DISABLED,
    NOT_INSTALLED,
    BUTLER_UPDATE_REQUIRED,
    MANAGER_UPDATE_REQUIRED,
    CONNECTED,
    PERMISSION_DENIED,
    CONNECTION_FAILED,
    CONNECTING,
    NOT_CONNECTED,
}

fun ShizukuSetupModule.Result?.toCardStatus(): AdbCardStatus = when {
    this == null || useShizuku != true -> AdbCardStatus.DISABLED
    !isInstalled -> AdbCardStatus.NOT_INSTALLED
    !isCompatible -> when {
        clientTooOld && !serverTooOld -> AdbCardStatus.BUTLER_UPDATE_REQUIRED
        else -> AdbCardStatus.MANAGER_UPDATE_REQUIRED
    }

    ourService -> AdbCardStatus.CONNECTED
    isPermissionDenied -> AdbCardStatus.PERMISSION_DENIED
    // Ahead of basicService: the server itself answering says nothing about our service, and reporting
    // "Connecting…" for a probe that already gave up is what left this card spinning forever.
    serviceState.isTerminalFailure -> AdbCardStatus.CONNECTION_FAILED
    basicService -> AdbCardStatus.CONNECTING
    else -> AdbCardStatus.NOT_CONNECTED
}

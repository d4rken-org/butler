package eu.darken.butler.common.adb.shizuku

/** The server held or refused by this process, independent of installed manager metadata. */
sealed interface AdbConnectionState {

    data object Disconnected : AdbConnectionState

    data class Connected(val server: AdbServer) : AdbConnectionState

    data class Incompatible(
        val backend: AdbBackend,
        val serverTooOld: Boolean,
        val clientTooOld: Boolean,
    ) : AdbConnectionState
}

package eu.darken.butler.common.adb.shizuku

/** Whether the connected server lets Butler through. */
sealed interface AdbPermissionState {

    data object Granted : AdbPermissionState

    /** @param permanentlyDenied the user chose "don't ask again", so a request is refused without a prompt */
    data class Denied(val permanentlyDenied: Boolean) : AdbPermissionState

    /** No connection to ask, or the server did not answer. Not a denial. */
    data object Unknown : AdbPermissionState
}

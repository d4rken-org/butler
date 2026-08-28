package eu.darken.butler.common.ipc

/**
 * The exception types a privileged host (root, ADB/Shizuku, isolated file service) can throw out of
 * a *synchronous* binder call, i.e. what [IpcErrorCodec] is able to rebuild by type on the other
 * side.
 *
 * Exceptions that are constructed in the app-side facades after the transaction already returned
 * (`ShellOpsException`, `PkgOpsException`, the root/ADB connection failures) never cross the binder
 * and deliberately have no code here.
 *
 * The enum entry NAME is the wire identity, never the ordinal: a host process can outlive an
 * in-place app update, so both sides of a transaction may come from different builds.
 *
 * That holds only while both sides speak the marker format. A host from a build predating it emits
 * no marker, and markerless carriers are passed through untouched, so such a host's errors surface
 * unrecognized until it restarts.
 */
enum class IpcErrorCode {
    PATH_READ,
    PATH_WRITE,
    PATH_ALREADY_EXISTS,
    PATH_PERMISSION_DENIED,
    PATH_UNKNOWN_FILE_TYPE,
    IO,
    SECURITY,
    ILLEGAL_ARGUMENT,

    /**
     * Anything outside the set above. Class name, message, causes and host stack still cross, but
     * the client rebuilds it as an [UnwrappedIPCException] instead of the original type.
     */
    UNMAPPED,
}

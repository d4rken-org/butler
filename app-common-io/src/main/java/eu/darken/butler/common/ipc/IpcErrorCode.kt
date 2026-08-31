package eu.darken.butler.common.ipc

/**
 * The exception types a privileged host (root, ADB/Shizuku, isolated file service) can fail with,
 * both out of a synchronous binder call and inside a streamed event, i.e. what [IpcErrorCodec] is
 * able to rebuild by type on the other side.
 *
 * Exceptions that are constructed in the app-side facades after the transaction already returned
 * (`ShellOpsException`, `PkgOpsException`, the root/ADB connection failures) never cross the binder
 * and deliberately have no code here.
 *
 * The enum entry NAME is the wire identity, never the ordinal. Build skew between the two sides does
 * not arise: every host reaching `FileOpsClient` belongs to this installation. Root and ADB hosts
 * are checked against our own identity on connect (`gateOnHostIdentity`), and the isolated service
 * runs in `:isolated`, a process of this package that dies when the package is replaced.
 *
 * A carrier without the marker is passed through untouched, so a null or plain-string error field
 * stays the message it was.
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

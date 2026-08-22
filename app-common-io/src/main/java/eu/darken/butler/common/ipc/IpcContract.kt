package eu.darken.butler.common.ipc

/**
 * Wire contract between the app and its privileged hosts (root, and ADB via Shizuku).
 *
 * The host runs out of our own APK but in a separate process, and that process can outlive an
 * in-place app update. The AIDL interfaces it exposes are *non-stable* AIDL, so transaction codes
 * are assigned by declaration order: a host built from a different revision than the client can
 * answer the wrong method for a given code instead of failing. [VERSION] plus the marker exchanged
 * through `checkBase()` turns that silent misdispatch into a detectable mismatch.
 *
 * Bump [VERSION] whenever the shape of anything reachable across that process boundary changes:
 * - `RootServiceConnection`, `AdbServiceConnection` and the interfaces they hand out
 *   (`FileOpsConnection`, `PkgOpsConnection`, `ShellOpsConnection`) plus their callbacks
 *   (e.g. `FileOperationCallback`).
 * - Any parcelable crossing that boundary, including `@Parcelize` classes. Reordering or retyping a
 *   field changes the wire format without touching a single `.aidl` file, so the compiler cannot
 *   catch it.
 *
 * A forgotten bump degrades to the old behaviour (possible misdispatch), so err towards bumping.
 */
object IpcContract {

    /**
     * 2: dropped clearCacheAsUser/clearCache/trimCaches from PkgOpsConnection, which renumbered
     * every transaction below them.
     */
    const val VERSION = 2

    private const val MARKER_PREFIX = "ipc-version:"

    /** First line of every `checkBase()` reply. */
    fun marker(): String = "$MARKER_PREFIX $VERSION"

    /**
     * A reply is only compatible when its very first line is our marker and carries exactly our
     * version. Anything else - a null reply, a host too old to emit a marker at all, a malformed or
     * out-of-range number, or a reply carrying more than one marker - counts as incompatible.
     */
    fun isCompatible(checkBaseReply: String?): Boolean {
        if (checkBaseReply == null) return false

        val lines = checkBaseReply.lineSequence().toList()
        if (lines.count { it.trimStart().startsWith(MARKER_PREFIX) } != 1) return false

        val first = lines.firstOrNull()?.trim() ?: return false
        if (!first.startsWith(MARKER_PREFIX)) return false

        // toIntOrNull() covers both "not a number" and values that overflow Int
        val version = first.removePrefix(MARKER_PREFIX).trim().toIntOrNull() ?: return false
        return version == VERSION
    }
}

/**
 * The connected host speaks a different [IpcContract.VERSION] than we do, i.e. it survived an
 * in-place app update. Thrown before any module client is handed out, so no caller can issue a
 * transaction against a host that would misdispatch it.
 *
 * Recovery is not automatic and the stale host is left running. `RootManager` invalidates its cached
 * state when the root binder changes, so restarting the root host is enough there. The Shizuku path
 * has no equivalent signal: restarting Butler's Shizuku user service need not change the base
 * Shizuku binder that clears `ShizukuManager`'s cache, so ADB access can keep reporting unavailable
 * until Butler itself is restarted. Tearing down and relaunching the host on mismatch is deliberately
 * left to a follow-up.
 */
class IpcContractMismatchException(
    message: String,
) : IllegalStateException(message)

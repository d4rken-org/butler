package eu.darken.butler.common.adb.shizuku

/**
 * How far away an ADB access server is, from a live connection to nothing installed at all.
 *
 * [packageName] is the app declaring the selected backend's permission, which names the manager to
 * show or open. Null where no app declares it any more, e.g. a manager uninstalled while its server
 * keeps running.
 */
sealed interface AdbAvailability {

    val backend: AdbBackend?
    val packageName: String?

    data object NotInstalled : AdbAvailability {
        override val backend: AdbBackend? = null
        override val packageName: String? = null
    }

    /** [packageName] declares [backend]'s permission but is not a manager the SDK knows, e.g. a renamed fork. */
    data class InstalledUnrecognized(
        override val backend: AdbBackend,
        override val packageName: String,
    ) : AdbAvailability

    data class InstalledNotConnected(
        override val backend: AdbBackend,
        override val packageName: String,
    ) : AdbAvailability

    /** A server answered and shares no protocol version with Butler's client. */
    data class Incompatible(
        override val backend: AdbBackend,
        override val packageName: String?,
        /** The user has to update the manager. */
        val serverTooOld: Boolean,
        /** Butler has to ship a newer client. */
        val clientTooOld: Boolean,
    ) : AdbAvailability

    data class Connected(
        override val backend: AdbBackend,
        override val packageName: String?,
    ) : AdbAvailability
}

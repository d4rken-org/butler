package eu.darken.butler.common.adb.shizuku

/** A live connection to an ADB access server. Two are equal only when they are the same connection. */
class AdbServer internal constructor(internal val connection: AdbServerConnection) {

    val backend: AdbBackend
        get() = connection.backend

    override fun equals(other: Any?): Boolean = other is AdbServer && other.connection == connection

    override fun hashCode(): Int = connection.hashCode()

    override fun toString(): String = "AdbServer(backend=$backend)"
}

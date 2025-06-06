package eu.darken.butler.common.sharedresource

import java.io.Closeable

interface KeepAlive : Closeable {
    val resourceId: String

    val isClosed: Boolean

    override fun close()
}
package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.files.local.LocalPath
import eu.darken.butler.common.files.remoteInputStream
import eu.darken.butler.common.ipc.RemoteInputStream
import okio.Source

data class DetailedInputSourceWrap(
    val path: LocalPath,
    val input: Source,
    val length: Long = -1
) : DetailedInputSource.Stub() {

    override fun path(): LocalPath = path

    override fun input(): RemoteInputStream = input.remoteInputStream()

    override fun length(): Long = length

}
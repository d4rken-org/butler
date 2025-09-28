package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.metadata.FileSystemInfo

interface FileSystemAction<P : APath> : GatewayAction<P> {
    suspend fun getInfo(path: P): FileSystemInfo
}
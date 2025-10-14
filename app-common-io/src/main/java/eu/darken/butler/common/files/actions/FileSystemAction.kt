package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.metadata.FileSystem

interface FileSystemAction<P : APath<P>> : GatewayAction<P> {
    suspend fun getFileSystem(path: P): FileSystem
}
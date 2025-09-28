package eu.darken.butler.explorer.core.engine

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

class BrowsingEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val homeLocationLoader: HomeLocationLoader,
    private val deviceLocationLoader: DeviceLocationLoader,
    private val directoryLoaderFactory: DirectoryLocationLoader.Factory,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "BrowsingEngine")
    private val directoryLoader = directoryLoaderFactory.create(workspaceId)

    suspend fun loadLocation(
        target: ExplorerNavigation.Target
    ): Flow<ExplorerLocation> = when (target) {
        is ExplorerNavigation.Target.Home -> homeLocationLoader.loadHome()
        is ExplorerNavigation.Target.Device -> deviceLocationLoader.loadDevice()
        is ExplorerNavigation.Target.Directory -> directoryLoader.loadDirectory(target.path)
    }.flowOn(dispatcherProvider.IO)

    fun hint(event: FileSystemEvent) {
        log(tag) { "hint(): $event" }
        // TODO: Refresh directory based on hints
        TODO("Not yet implemented")
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): BrowsingEngine
    }
}
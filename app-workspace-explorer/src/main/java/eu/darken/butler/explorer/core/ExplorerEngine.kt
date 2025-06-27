package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.explorer.ui.explorer.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

class ExplorerEngine @Inject constructor() {

    suspend fun getHomeEntry(): ExplorerEntry = withContext(Dispatchers.IO) {
        ExplorerEntry.SyntheticOverview(
            path = RawPath.Companion.build("HOME"),
            displayName = "Home",
            quickAccessItems = listOf(
                ExplorerEntry.SyntheticOverview.QuickAccessItem(
                    path = RawPath.Companion.build("/"),
                    displayName = "Phone",
                    icon = "phone"
                ),
                ExplorerEntry.SyntheticOverview.QuickAccessItem(
                    path = RawPath.Companion.build("/storage/emulated/0"),
                    displayName = "Internal Storage",
                    icon = "folder"
                ),
                ExplorerEntry.SyntheticOverview.QuickAccessItem(
                    path = RawPath.Companion.build("/sdcard"),
                    displayName = "SD Card",
                    icon = "sd_card"
                )
            )
        )
    }

    suspend fun getContent(path: APath): List<FileItem> = withContext(Dispatchers.IO) {
        when (path.toString()) {
            "HOME" -> {
                val homeEntry = getHomeEntry()
                if (homeEntry is ExplorerEntry.SyntheticOverview) {
                    // Convert quick access items to FileItems for display
                    homeEntry.quickAccessItems.map { item ->
                        createQuickAccessFileItem(item)
                    }
                } else {
                    emptyList()
                }
            }
            else -> {
                // TODO: Implement real file system integration using GatewaySwitch
                emptyList()
            }
        }
    }

    private fun createQuickAccessFileItem(item: ExplorerEntry.SyntheticOverview.QuickAccessItem): FileItem.Directory {
        val mockLookup = object : APathLookup<RawPath> {
            override val lookedUp: RawPath = item.path as RawPath
            override val size: Long = 0L
            override val fileType: FileType = FileType.DIRECTORY
            override val modifiedAt: Instant = Instant.now()
            override val target: RawPath? = null
        }
        
        return FileItem.Directory(
            lookup = mockLookup,
            mimeType = "inode/directory",
            isSelected = false,
            childCount = null
        )
    }

    suspend fun navigate(item: FileItem): ExplorerEntry? = withContext(Dispatchers.IO) {
        when (item) {
            is FileItem.Directory -> ExplorerEntry.Directory(
                path = item.lookup.lookedUp,
                displayName = item.lookup.name,
                itemCount = null
            )
            else -> null
        }
    }

    suspend fun loadDirectory(path: APath): Flow<List<FileItem>> = flow {
        emit(getContent(path))
    }.flowOn(Dispatchers.IO)

    suspend fun refreshDirectory(path: APath): List<FileItem> = getContent(path)

    suspend fun getDirectoryItemCount(path: APath): Int? = withContext(Dispatchers.IO) {
        // TODO: Implement real file system integration
        null
    }

    suspend fun isPathValid(path: APath): Boolean = withContext(Dispatchers.IO) {
        // TODO: Implement real file system integration
        true
    }
}
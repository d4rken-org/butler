package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.recyclebin.RecycleBinRepo
import eu.darken.butler.explorer.R
import eu.darken.butler.permissions.core.PathRequirements
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecycleBinLocationLoader @Inject constructor(
    private val recycleBinRepo: RecycleBinRepo,
) {

    private val tag = logTag("Explorer", "RecycleBinLocationLoader")

    private suspend fun checkLocationRequirements(): PathRequirements {
        log(tag) { "checkLocationRequirements(): Checking requirements for RecycleBin" }
        return PathRequirements()
    }

    fun loadRecycleBin(): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadRecycleBin(): Loading recycle bin location" }

        val setupRequirements = checkLocationRequirements()
        val context = LocationLoaderContext(
            initialState = ExplorerLocation.RecycleBin(
                setupRequirements = setupRequirements,
                progress = Progress.Data(
                    primary = R.string.explorer_loader_progress_recyclebin_loading.toCaString(),
                ),
            ),
            emit = ::emit
        )
        context.emitState()

        try {
            // Get all recycle bin items
            val recycleBinItems = recycleBinRepo.getAllItems().first()
            log(tag, DEBUG) { "loadRecycleBin(): Found ${recycleBinItems.size} items in recycle bin" }

            // Convert repository items to ExplorerItems
            val explorerItems = recycleBinItems.map { item ->
                ExplorerItem.RecycleBinItem(
                    itemId = item.id,
                    originalLookup = item.originalLookup,
                    recycleBinLookup = item.recycleBinLookup,
                    deletedAt = item.deletedAt,
                )
            }.sortedByDescending { it.deletedAt }

            val info = ExplorerLocation.RecycleBin.Info(
                itemCount = recycleBinItems.size,
                totalSize = recycleBinItems.sumOf { it.size },
                oldestItem = recycleBinItems.minByOrNull { it.deletedAt }?.deletedAt,
            )

            log(tag, INFO) { "loadRecycleBin(): Loaded ${explorerItems.size} items" }

            context.updateState {
                copy(
                    items = explorerItems,
                    info = info,
                    progress = null,
                )
            }
            context.emitState()
        } catch (e: Exception) {
            log(tag, ERROR) { "loadRecycleBin(): Failed to load recycle bin: ${e.asLog()}" }
            throw e
        }
    }
}
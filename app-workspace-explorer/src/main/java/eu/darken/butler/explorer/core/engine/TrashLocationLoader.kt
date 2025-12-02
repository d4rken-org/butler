package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.explorer.R
import eu.darken.butler.permissions.core.PathRequirements
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrashLocationLoader @Inject constructor(
    private val trashRepo: TrashRepo,
) {

    // TODO inject workspace id?
    private val tag = logTag("Explorer", "Trash", "LocationLoader")

    private suspend fun checkLocationRequirements(): PathRequirements {
        log(tag) { "checkLocationRequirements(): Checking requirements for trash" }
        return PathRequirements()
    }

    fun loadTrash(): Flow<ExplorerLocation> = flow {
        log(tag, INFO) { "loadTrash(): Loading trash location" }

        val setupRequirements = checkLocationRequirements()
        val context = LocationLoaderContext(
            initialState = ExplorerLocation.Trash(
                setupRequirements = setupRequirements,
                progress = Progress.Data(
                    primary = R.string.explorer_loader_progress_trash_loading.toCaString(),
                ),
            ),
            emit = ::emit
        )
        context.emitState()

        try {
            // Get all trash items
            val trashItems = trashRepo.getAllItems().first()
            log(tag, DEBUG) { "loadTrash(): Found ${trashItems.size} items in trash" }

            // Convert repository items to ExplorerItems
            val explorerItems = trashItems.map { item ->
                ExplorerItem.TrashItem(
                    itemId = item.id,
                    originalLookup = item.originalLookup,
                    trashLookup = item.trashLookup,
                    deletedAt = item.deletedAt,
                )
            }.sortedByDescending { it.deletedAt }

            val info = ExplorerLocation.Trash.Info(
                itemCount = trashItems.size,
                totalSize = trashItems.sumOf { it.size },
                oldestItem = trashItems.minByOrNull { it.deletedAt }?.deletedAt,
            )

            log(tag, INFO) { "loadTrash(): Loaded ${explorerItems.size} items" }

            context.updateState {
                copy(
                    items = explorerItems,
                    info = info,
                    progress = null,
                )
            }
            context.emitState()
        } catch (e: Exception) {
            log(tag, ERROR) { "loadTrash(): Failed to load trash: ${e.asLog()}" }
            throw e
        }
    }
}
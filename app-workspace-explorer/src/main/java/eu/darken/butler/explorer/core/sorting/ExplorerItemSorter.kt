package eu.darken.butler.explorer.core.sorting

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.workspace.core.Workspace
import kotlin.time.Instant

class ExplorerItemSorter @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @ApplicationContext private val context: Context,
) {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Page", "ItemSorter")

    fun sortItems(
        items: List<ExplorerItem>,
        sortSettings: SortSettings,
    ): List<ExplorerItem> {
        log(tag, VERBOSE) { "sortItems: ${items.size} items with settings=$sortSettings" }

        val shortcuts = mutableListOf<ExplorerItem.Shortcut>()
        val storage = mutableListOf<ExplorerItem.Storage>()
        val pathItems = mutableListOf<ExplorerItem.Path>()
        val trashItems = mutableListOf<ExplorerItem.TrashItem>()

        items.forEach { item ->
            when (item) {
                is ExplorerItem.Shortcut -> shortcuts.add(item)
                is ExplorerItem.Storage -> storage.add(item)
                is ExplorerItem.Path -> pathItems.add(item)
                is ExplorerItem.TrashItem -> trashItems.add(item)
            }
        }

        val sortedPathItems = sortPathItems(context, pathItems, sortSettings)
        // Sort trash items by deletion date (most recent first)
        val sortedTrashItems = trashItems.sortedByDescending { it.deletedAt }

        return if (sortSettings.reversed) {
            sortedTrashItems + sortedPathItems + storage + shortcuts
        } else {
            shortcuts + storage + sortedPathItems + sortedTrashItems
        }
    }

    private fun sortPathItems(
        context: Context,
        pathItems: List<ExplorerItem.Path>,
        sortSettings: SortSettings,
    ): List<ExplorerItem.Path> {
        if (pathItems.isEmpty()) return pathItems

        val peeks = pathItems.filterIsInstance<ExplorerItem.Peek>()
        val directories = pathItems.filterIsInstance<ExplorerItem.Directory>()
        val files = pathItems.filterIsInstance<ExplorerItem.File>()

        val sortedDirectories = applySortMode(context, directories, sortSettings)
        val sortedFiles = applySortMode(context, files, sortSettings)

        return if (sortSettings.reversed) {
            peeks + sortedFiles.reversed() + sortedDirectories.reversed()
        } else {
            peeks + sortedDirectories + sortedFiles
        }
    }

    private fun <T : ExplorerItem.Path> applySortMode(
        context: Context,
        items: List<T>,
        sortSettings: SortSettings,
    ): List<T> {
        return when (sortSettings.mode) {
            SortSettings.Mode.NAME -> items.sortedWith { a, b ->
                NaturalSortComparator.compare(a.displayName.get(context), b.displayName.get(context))
            }
            SortSettings.Mode.SIZE -> items.sortedWith { a, b ->
                if (a is ExplorerItem.Lookup && b is ExplorerItem.Lookup) {
                    val sizeA = a.lookup.size ?: 0L
                    val sizeB = b.lookup.size ?: 0L
                    sizeA.compareTo(sizeB)
                } else {
                    0
                }
            }
            SortSettings.Mode.MODIFIED_AT -> items.sortedWith { a, b ->
                if (a is ExplorerItem.Lookup && b is ExplorerItem.Lookup) {
                    val timeA = a.lookup.modifiedAt ?: Instant.DISTANT_PAST
                    val timeB = b.lookup.modifiedAt ?: Instant.DISTANT_PAST
                    timeA.compareTo(timeB)
                } else {
                    0
                }
            }
            SortSettings.Mode.CREATED_AT -> {
                items.sortedWith { a, b ->
                    if (a is ExplorerItem.Lookup && b is ExplorerItem.Lookup) {
                        val timeA = a.createdAt
                        val timeB = b.createdAt
                        when {
                            timeA == null && timeB == null -> 0
                            timeA == null -> -1
                            timeB == null -> 1
                            else -> timeA.compareTo(timeB)
                        }
                    } else {
                        0
                    }
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerItemSorter
    }
}
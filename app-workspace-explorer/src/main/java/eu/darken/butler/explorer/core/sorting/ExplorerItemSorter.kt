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
        val pathItems = mutableListOf<ExplorerItem.PathItem>()

        items.forEach { item ->
            when (item) {
                is ExplorerItem.Shortcut -> shortcuts.add(item)
                is ExplorerItem.PathItem -> pathItems.add(item)
            }
        }

        val sortedPathItems = sortPathItems(context, pathItems, sortSettings)

        return if (sortSettings.reversed) {
            sortedPathItems + shortcuts
        } else {
            shortcuts + sortedPathItems
        }
    }

    private fun sortPathItems(
        context: Context,
        pathItems: List<ExplorerItem.PathItem>,
        sortSettings: SortSettings,
    ): List<ExplorerItem.PathItem> {
        if (pathItems.isEmpty()) return pathItems

        val directories = pathItems.filterIsInstance<ExplorerItem.DirectoryItem>()
        val files = pathItems.filterIsInstance<ExplorerItem.FileItem>()

        val sortedDirectories = applySortMode(context, directories, sortSettings)
        val sortedFiles = applySortMode(context, files, sortSettings)

        return if (sortSettings.reversed) {
            sortedFiles.reversed() + sortedDirectories.reversed()
        } else {
            sortedDirectories + sortedFiles
        }
    }

    private fun <T : ExplorerItem.PathItem> applySortMode(
        context: Context,
        items: List<T>,
        sortSettings: SortSettings,
    ): List<T> {
        return when (sortSettings.mode) {
            SortSettings.Mode.NAME -> items.sortedWith { a, b ->
                NaturalSortComparator.compare(a.displayName.get(context), b.displayName.get(context))
            }
            SortSettings.Mode.SIZE -> items.sortedWith { a, b ->
                val sizeA = a.lookup.size ?: 0L
                val sizeB = b.lookup.size ?: 0L
                sizeA.compareTo(sizeB)
            }
            SortSettings.Mode.MODIFIED_AT -> items.sortedWith { a, b ->
                val timeA = a.lookup.modifiedAt
                val timeB = b.lookup.modifiedAt
                when {
                    timeA == null && timeB == null -> 0
                    timeA == null -> -1
                    timeB == null -> 1
                    else -> timeA.compareTo(timeB)
                }
            }
            SortSettings.Mode.CREATED_AT -> {
                log(tag, WARN) { "CREATED_AT sort mode not supported, falling back to MODIFIED_AT" }
                items.sortedWith { a, b ->
                    val timeA = a.lookup.modifiedAt
                    val timeB = b.lookup.modifiedAt
                    when {
                        timeA == null && timeB == null -> 0
                        timeA == null -> -1
                        timeB == null -> 1
                        else -> timeA.compareTo(timeB)
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
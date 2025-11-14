package eu.darken.butler.searcher.core.sorting

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.workspace.core.Workspace
import kotlin.time.Instant

class SearchItemSorter @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @ApplicationContext private val context: Context,
) {

    private val tag = logTag("Searcher", "Workspace", workspaceId.shortTag, "Page", "ItemSorter")

    fun sortItems(
        items: List<SearchItem>,
        sortSettings: SearchSortSettings,
    ): List<SearchItem> {
        log(tag, VERBOSE) { "sortItems: ${items.size} items with settings=$sortSettings" }

        if (items.isEmpty()) return items

        val directories = items.filterIsInstance<SearchItem.Directory>()
        val files = items.filterIsInstance<SearchItem.File>()

        val sortedDirectories = applySortMode(directories, sortSettings)
        val sortedFiles = applySortMode(files, sortSettings)

        return if (sortSettings.reversed) {
            sortedFiles.reversed() + sortedDirectories.reversed()
        } else {
            sortedDirectories + sortedFiles
        }
    }

    private fun <T : SearchItem> applySortMode(
        items: List<T>,
        sortSettings: SearchSortSettings,
    ): List<T> {
        return when (sortSettings.mode) {
            SearchSortSettings.Mode.NAME -> items.sortedWith { a, b ->
                naturalCompare(a.name, b.name)
            }
            SearchSortSettings.Mode.SIZE -> items.sortedWith { a, b ->
                val sizeA = a.size ?: 0L
                val sizeB = b.size ?: 0L
                sizeA.compareTo(sizeB)
            }
            SearchSortSettings.Mode.MODIFIED_AT -> items.sortedWith { a, b ->
                val timeA = a.modifiedAt ?: Instant.DISTANT_PAST
                val timeB = b.modifiedAt ?: Instant.DISTANT_PAST
                timeA.compareTo(timeB)
            }
            SearchSortSettings.Mode.CREATED_AT -> {
                items.sortedWith { a, b ->
                    val timeA = a.lookup.createdAt
                    val timeB = b.lookup.createdAt
                    when {
                        timeA == null && timeB == null -> 0
                        timeA == null -> -1
                        timeB == null -> 1
                        else -> timeA.compareTo(timeB)
                    }
                }
            }
            SearchSortSettings.Mode.PATH -> items.sortedWith { a, b ->
                naturalCompare(a.path.path, b.path.path)
            }
        }
    }

    private fun naturalCompare(s1: String, s2: String): Int {
        var i1 = 0
        var i2 = 0

        while (i1 < s1.length && i2 < s2.length) {
            val c1 = s1[i1]
            val c2 = s2[i2]

            if (c1.isDigit() && c2.isDigit()) {
                val num1 = extractNumber(s1, i1)
                val num2 = extractNumber(s2, i2)

                val numResult = when {
                    num1 == null && num2 == null -> 0
                    num1 == null -> -1
                    num2 == null -> 1
                    else -> num1.compareTo(num2)
                }

                if (numResult != 0) return numResult

                i1 += getNumberLength(s1, i1)
                i2 += getNumberLength(s2, i2)
            } else {
                val charResult = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (charResult != 0) return charResult

                i1++
                i2++
            }
        }

        return s1.length.compareTo(s2.length)
    }

    private fun extractNumber(s: String, start: Int): Long? {
        if (start >= s.length || !s[start].isDigit()) return null

        var end = start
        while (end < s.length && s[end].isDigit()) {
            end++
        }

        return try {
            s.substring(start, end).toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun getNumberLength(s: String, start: Int): Int {
        var length = 0
        var i = start
        while (i < s.length && s[i].isDigit()) {
            length++
            i++
        }
        return length
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): SearchItemSorter
    }
}

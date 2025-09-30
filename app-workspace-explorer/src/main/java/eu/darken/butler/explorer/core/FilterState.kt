package eu.darken.butler.explorer.core

data class FilterState(
    val includePattern: String = "",
    val excludePattern: String = "",
    val fileTypeFilter: FileTypeFilter = FileTypeFilter.ALL,
)

enum class FileTypeFilter {
    ALL,
    FILES_ONLY,
    FOLDERS_ONLY
}
package eu.darken.butler.explorer.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A tab's name/type filtering. Survives process death with the session, dies with the tab.
 *
 * The JSON shape below and the slot key in [ExplorerTabViewStore] are wire contract - the workspace
 * layer stores this payload opaquely, so nothing else would notice a format break.
 */
@Serializable
data class FilterState(
    @SerialName("include") val includePattern: String = "",
    @SerialName("exclude") val excludePattern: String = "",
    @SerialName("type") val fileTypeFilter: FileTypeFilter = FileTypeFilter.ALL,
)

@Serializable
enum class FileTypeFilter {
    @SerialName("all") ALL,
    @SerialName("files") FILES_ONLY,
    @SerialName("folders") FOLDERS_ONLY,
}

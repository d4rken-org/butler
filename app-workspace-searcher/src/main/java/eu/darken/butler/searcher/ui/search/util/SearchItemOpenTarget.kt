package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase

/**
 * A [SearchItem] has no mime type, so text-ness is derived from the path here. Shared by the
 * single-item "Open" action and the multi-select "Open in new tabs", so both feed the same
 * classification in [OpenInNewTabsUseCase].
 */
fun SearchItem.toOpenInNewTabsItem(): OpenInNewTabsUseCase.Item = when (fileType) {
    FileType.DIRECTORY -> OpenInNewTabsUseCase.Item.Directory(path)
    else -> OpenInNewTabsUseCase.Item.File(path, isText = TextFileDetector.isTextFile(path))
}

package eu.darken.butler.common.picker.core

import eu.darken.butler.common.files.APath

data class FilePickerState(
    val currentPath: APath? = null,
    val items: List<FileItem> = emptyList(),
    val selectedItems: Set<APath> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val showHiddenFiles: Boolean = false,
) {
    val canConfirm: Boolean
        get() = selectedItems.isNotEmpty()
    
    data class FileItem(
        val path: APath,
        val name: String,
        val isDirectory: Boolean,
        val size: Long? = null,
        val lastModified: Long? = null,
        val isHidden: Boolean = false,
    )
}
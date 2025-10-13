package eu.darken.butler.common.picker.core

import android.os.Parcelable
import eu.darken.butler.common.files.APath
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class FilePickerConfig(
    val mode: SelectionMode = SelectionMode.SingleFile,
    val initialPath: APath<*>? = null,
    val filters: List<String> = emptyList(),
    val showHiddenFiles: Boolean = false,
    val allowCreateFolder: Boolean = true,
    val title: String? = null,
    val subtitle: String? = null,
    val quickAccessPaths: List<APath<*>> = emptyList(),
) : Parcelable

enum class SelectionMode {
    SingleFile,
    MultipleFiles,
    SingleFolder,
    MultipleFolders,
    Mixed
}
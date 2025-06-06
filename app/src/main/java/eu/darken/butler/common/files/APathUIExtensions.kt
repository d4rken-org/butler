package eu.darken.butler.common.files

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import eu.darken.butler.R

@get:DrawableRes
val FileType.iconRes: Int
    get() = when (this) {
        FileType.DIRECTORY -> R.drawable.ic_folder
        FileType.SYMBOLIC_LINK -> R.drawable.ic_file_link
        FileType.FILE -> R.drawable.ic_file
        FileType.UNKNOWN -> R.drawable.file_question
    }

@get:StringRes
val FileType.labelRes: Int
    get() = when (this) {
        FileType.DIRECTORY -> eu.darken.butler.common.R.string.file_type_directory
        FileType.SYMBOLIC_LINK -> eu.darken.butler.common.R.string.file_type_symbolic_link
        FileType.FILE -> eu.darken.butler.common.R.string.file_type_file
        FileType.UNKNOWN -> eu.darken.butler.common.R.string.file_type_unknown
    }
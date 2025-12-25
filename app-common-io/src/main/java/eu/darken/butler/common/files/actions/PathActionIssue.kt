package eu.darken.butler.common.files.actions

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.issue.Issue

sealed interface PathActionIssue : Issue {

    interface Resolution : Issue.Resolution

    data class PathAlreadyExists(
        override val id: Issue.Id = Issue.Id(),
        val source: APathLookup<out APath<*>>? = null,
        val destination: APathLookup<out APath<*>>,
        val canSkip: Boolean = false,
        val canOverwrite: Boolean = false,
        val canMerge: Boolean = false,
        val canRenameSource: Boolean = false,
        val canRenameDestination: Boolean = false,
        val suggestedName: String? = null,
    ) : PathActionIssue {
        override val title: CaString = caString {
            if (destination.fileType == eu.darken.butler.common.files.metadata.FileType.DIRECTORY) {
                getString(eu.darken.butler.common.io.R.string.path_action_folder_exists_title)
            } else {
                getString(eu.darken.butler.common.io.R.string.path_action_file_exists_title)
            }
        }
        override val description: CaString = caString {
            val parentPath = destination.lookedUp.parent?.path ?: "/"
            if (destination.fileType == eu.darken.butler.common.files.metadata.FileType.DIRECTORY) {
                getString(
                    eu.darken.butler.common.io.R.string.path_action_folder_exists_description,
                    destination.lookedUp.name,
                    parentPath
                )
            } else {
                getString(
                    eu.darken.butler.common.io.R.string.path_action_file_exists_description,
                    destination.lookedUp.name,
                    parentPath
                )
            }
        }

        sealed interface Resolution : PathActionIssue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data class Overwrite(val applyToAll: Boolean = false) : Resolution
            data class RenameSource(val newName: String, val applyToAll: Boolean = false) : Resolution
            data class RenameDestination(val newName: String, val applyToAll: Boolean = false) : Resolution
            data class Merge(val applyToAll: Boolean = false) : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }

    data class InsufficientPermission(
        override val id: Issue.Id = Issue.Id(),
        val source: APathLookup<out APath<*>>? = null,
        val destinationPath: APath<*>,
        val canSkip: Boolean = false,
        val exception: Throwable? = null,
    ) : PathActionIssue {
        override val title: CaString = caString {
            getString(eu.darken.butler.common.io.R.string.path_action_permission_denied_title)
        }
        override val description: CaString = caString {
            val parentPath = destinationPath.parent?.path ?: "/"
            getString(
                eu.darken.butler.common.io.R.string.path_action_permission_denied_description,
                destinationPath.name,
                parentPath
            )
        }

        sealed interface Resolution : PathActionIssue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }

    data class InsufficientSpace(
        override val id: Issue.Id = Issue.Id(),
        val source: APathLookup<out APath<*>>,
        val destinationPath: APath<*>,
    ) : PathActionIssue {
        override val title: CaString = caString {
            getString(eu.darken.butler.common.io.R.string.path_action_insufficient_space_title)
        }
        override val description: CaString = caString {
            val parentPath = source.lookedUp.parent?.path ?: "/"
            getString(
                eu.darken.butler.common.io.R.string.path_action_insufficient_space_description,
                source.lookedUp.name,
                parentPath
            )
        }

        sealed interface Resolution : PathActionIssue.Resolution {
            data object Retry : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }

    data class UnknownError(
        override val id: Issue.Id = Issue.Id(),
        val source: APathLookup<out APath<*>>? = null,
        val destinationPath: APath<*>? = null,
        val exception: Throwable,
        val errorMessage: CaString = caString { exception.localized(it).description.get(it) },
        val canSkip: Boolean = false,
        val canRetry: Boolean = false,
    ) : PathActionIssue {
        override val title: CaString = caString {
            getString(eu.darken.butler.common.io.R.string.path_action_unknown_error_title)
        }
        override val description: CaString = errorMessage

        sealed interface Resolution : PathActionIssue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data object Retry : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }

    data class TrashSizeLimitExceeded(
        override val id: Issue.Id = Issue.Id(),
        val totalSize: Long,
        val itemCount: Int,
        val trashMaxSize: Long,
    ) : PathActionIssue {
        override val title: CaString = caString {
            getString(eu.darken.butler.common.io.R.string.path_action_trash_size_limit_title)
        }
        override val description: CaString = caString {
            getString(
                eu.darken.butler.common.io.R.string.path_action_trash_size_limit_description,
                itemCount,
                android.text.format.Formatter.formatFileSize(it, totalSize),
                android.text.format.Formatter.formatFileSize(it, trashMaxSize),
            )
        }

        sealed interface Resolution : PathActionIssue.Resolution {
            data object DeletePermanently : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }

    data class TrashMoveFailed(
        override val id: Issue.Id = Issue.Id(),
        val failedItems: List<APathLookup<out APath<*>>>,
        val exception: Throwable? = null,
    ) : PathActionIssue {
        override val title: CaString = caString {
            getString(eu.darken.butler.common.io.R.string.path_action_trash_move_failed_title)
        }
        override val description: CaString = caString {
            getString(eu.darken.butler.common.io.R.string.path_action_trash_move_failed_description, failedItems.size)
        }

        sealed interface Resolution : PathActionIssue.Resolution {
            data object DeletePermanently : Resolution
            data object Skip : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }
}
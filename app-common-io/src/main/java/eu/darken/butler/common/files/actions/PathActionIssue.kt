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
        val source: APathLookup<APath>? = null,
        val destination: APathLookup<APath>,
        val canSkip: Boolean = false,
        val canOverwrite: Boolean = false,
        val canMerge: Boolean = false,
        val canRenameSource: Boolean = false,
        val canRenameDestination: Boolean = false,
        val suggestedName: String? = null,
    ) : PathActionIssue {
        override val title: CaString = caString {
            "// TODO: title PathAlreadyExists"
        }
        override val description: CaString = caString {
            "// TODO: description PathAlreadyExists"
        }
        sealed interface Resolution : PathActionIssue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data class Overwrite(val applyToAll: Boolean = false) : Resolution
            data class RenameSource(val newName: String) : Resolution
            data class RenameDestination(val newName: String) : Resolution
            data class Merge(val applyToAll: Boolean = false) : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }

    data class InsufficientPermission(
        override val id: Issue.Id = Issue.Id(),
        val source: APathLookup<APath>? = null,
        val destination: APathLookup<APath>,
        val canSkip: Boolean = false,
        val exception: Throwable? = null,
    ) : PathActionIssue {
        override val title: CaString = caString {
            "// TODO: title InsufficientPermission"
        }
        override val description: CaString = caString {
            "// TODO: description InsufficientPermission"
        }
        sealed interface Resolution : PathActionIssue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }

    data class InsufficientSpace(
        override val id: Issue.Id = Issue.Id(),
        val source: APathLookup<APath>,
        val destination: APathLookup<APath>,
        val canSkip: Boolean = false,
    ) : PathActionIssue {
        override val title: CaString = caString {
            "// TODO: title InsufficientSpace"
        }
        override val description: CaString = caString {
            "// TODO: description InsufficientSpace"
        }
        sealed interface Resolution : PathActionIssue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }

    data class UnknownError(
        override val id: Issue.Id = Issue.Id(),
        val source: APathLookup<APath>? = null,
        val destination: APathLookup<APath>? = null,
        val exception: Throwable,
        val errorMessage: CaString = caString { exception.localized(it).description.get(it) },
        val canSkip: Boolean = false,
        val canRetry: Boolean = false,
    ) : PathActionIssue {
        override val title: CaString = caString {
            "// TODO: title UnknownError"
        }
        override val description: CaString = caString {
            "// TODO: description UnknownError"
        }
        sealed interface Resolution : PathActionIssue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data class Retry(val applyToAll: Boolean = false) : Resolution
            data class Cancel(val error: Exception? = null) : Resolution
        }
    }
}
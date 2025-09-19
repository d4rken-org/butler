package eu.darken.butler.common.files.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlin.uuid.Uuid

sealed interface Issue {
    val issueId: IssueId

    data class IssueId(
        val id: Uuid = Uuid.random(),
    )

    interface Resolution

    data class PathAlreadyExists(
        override val issueId: IssueId = IssueId(),
        val source: APathLookup<APath>? = null,
        val destination: APathLookup<APath>,
        val canSkip: Boolean = false,
        val canOverwrite: Boolean = false,
        val canMerge: Boolean = false,
        val canRenameSource: Boolean = false,
        val canRenameDestination: Boolean = false,
        val suggestedName: String? = null,
    ) : Issue {
        sealed interface Resolution : Issue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data class Overwrite(val applyToAll: Boolean = false) : Resolution
            data class RenameSource(val newName: String) : Resolution
            data class RenameDestination(val newName: String) : Resolution
            data class Merge(val applyToAll: Boolean = false) : Resolution
            data object Cancel : Resolution
        }
    }


    data class InsufficientPermission(
        override val issueId: IssueId = IssueId(),
        val source: APathLookup<APath>,
        val destination: APathLookup<APath>,
        val canSkip: Boolean = false,
    ) : Issue {
        sealed interface Resolution : Issue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data object Cancel : Resolution
        }
    }

    data class InsufficientSpace(
        override val issueId: IssueId = IssueId(),
        val source: APathLookup<APath>,
        val destination: APathLookup<APath>,
        val canSkip: Boolean = false,
    ) : Issue {
        sealed interface Resolution : Issue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data object Cancel : Resolution
        }
    }

    data class UnknownError(
        override val issueId: IssueId = IssueId(),
        val source: APathLookup<APath>? = null,
        val destination: APathLookup<APath>? = null,
        val exception: Throwable,
        val errorMessage: CaString = caString { exception.localized(it).description.get(it) },
        val canSkip: Boolean = false,
        val canRetry: Boolean = false,
    ) : Issue {
        sealed interface Resolution : Issue.Resolution {
            data class Skip(val applyToAll: Boolean = false) : Resolution
            data class Retry(val applyToAll: Boolean = false) : Resolution
            data object Cancel : Resolution
        }
    }
}
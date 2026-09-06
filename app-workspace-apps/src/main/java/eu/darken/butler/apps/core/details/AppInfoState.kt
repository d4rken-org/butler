package eu.darken.butler.apps.core.details

/**
 * What the package repository can say about the app this workspace was opened for.
 *
 * "Uninstalled for this user" is not a state of its own: the package is still known and its
 * metadata still valid, so it is [Ready] with [AppInfo.isUninstalled] set.
 */
sealed interface AppInfoState {

    data object Loading : AppInfoState

    data class Ready(val info: AppInfo) : AppInfoState

    /** The package source itself failed. Says nothing about whether the app is still installed. */
    data class SourceError(val error: Throwable) : AppInfoState

    data object Gone : AppInfoState
}

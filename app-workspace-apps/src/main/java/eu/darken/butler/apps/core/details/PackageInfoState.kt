package eu.darken.butler.apps.core.details

import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo

/** What the Package info route knows about the app's manifest, permissions and signing certificates. */
sealed interface PackageInfoState {
    data object Loading : PackageInfoState

    /** Neither the package query nor the APK on disk yielded anything readable. */
    data object Unavailable : PackageInfoState

    data class Ready(val info: ApkArchiveInfo) : PackageInfoState
}

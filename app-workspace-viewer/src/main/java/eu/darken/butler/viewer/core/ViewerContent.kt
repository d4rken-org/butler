package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo

/**
 * What the viewer resolved the target file to. [Loading] is the seed state, everything else is
 * terminal for one load attempt.
 */
sealed interface ViewerContent {
    data object Loading : ViewerContent

    data class Image(val mime: MimeInfo) : ViewerContent

    data class Apk(
        val mime: MimeInfo,
        val apkInfo: ApkArchiveInfo,
        val installState: ApkInstallState,
    ) : ViewerContent

    /** A PDF whose first page can be rendered. The bitmap is not part of the state, only its existence. */
    data class PdfPreview(val mime: MimeInfo, val pageCount: Int) : ViewerContent

    data class Unsupported(val mime: MimeInfo) : ViewerContent

    data class Failed(val error: Throwable) : ViewerContent
}

/** How the archive relates to what is installed on this device. */
sealed interface ApkInstallState {
    data class Installed(
        val versionName: String?,
        val versionCode: Long,
        val comparison: VersionComparison,
    ) : ApkInstallState

    data object NotInstalled : ApkInstallState

    /** The lookup itself failed - must never be rendered as "not installed". */
    data object Unknown : ApkInstallState
}

/**
 * Version codes only. Signer equality is not checked, so the UI must not imply the archive would
 * actually install over what is there.
 */
enum class VersionComparison {
    SAME,
    APK_NEWER,
    INSTALLED_NEWER,
}

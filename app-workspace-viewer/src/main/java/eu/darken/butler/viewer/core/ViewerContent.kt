package eu.darken.butler.viewer.core

import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo
import eu.darken.butler.common.pkgs.installer.AppInstallFormat

/**
 * What the viewer resolved the target file to. [Loading] is the seed state, everything else is
 * terminal for one load attempt.
 */
sealed interface ViewerContent {
    data object Loading : ViewerContent

    data class Image(val mime: MimeInfo) : ViewerContent

    /**
     * A file the text preview can render. The text itself is not part of the state, only the fact
     * that there is some - the same split [PdfPreview] makes, and for the same reason: a paused tab
     * must not keep the payload alive.
     */
    data class Text(val mime: MimeInfo) : ViewerContent

    data class Apk(
        val mime: MimeInfo,
        val apkInfo: ApkArchiveInfo,
        val installState: ApkInstallState,
    ) : ViewerContent

    /**
     * A multi-APK install container. It is also a browsable zip, so it keeps the browse offer that
     * [Archive] has - what it does not keep is being browsed on tap, because installing it is the
     * more likely intent.
     */
    data class AppBundle(
        val mime: MimeInfo,
        val format: AppInstallFormat,
        val apkInfo: ApkArchiveInfo,
        val splitCount: Int,
        val hasObb: Boolean,
        /** Expansion files are present but nothing here can write them without root or ADB. */
        val needsElevationForObb: Boolean,
        val installState: ApkInstallState,
    ) : ViewerContent

    /** A PDF whose pages can be rendered. The bitmap is not part of the state, only its existence. */
    data class PdfPreview(val mime: MimeInfo, val pageCount: Int) : ViewerContent

    /**
     * A container the Explorer can browse. The viewer renders nothing for it - it says what this is
     * and how to get into it, which is a different answer from "not supported".
     */
    data class Archive(
        val mime: MimeInfo,
        val format: ArchiveFormat,
        val access: Access,
    ) : ViewerContent {

        /**
         * What can actually be done with this container from here. Carried on the state rather than
         * re-derived in the UI, because the two reasons a container cannot be browsed need
         * different answers and only one of them has an action at all.
         */
        enum class Access {
            /** A real file outside any other archive: the Explorer opens it in place. */
            BROWSABLE,

            /** Streamed from another app: it has to be written somewhere before it can be browsed. */
            NEEDS_COPY,

            /** An archive inside an archive. Butler does not open those, and saving cannot serve it. */
            NESTED,
            ;
        }
    }

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

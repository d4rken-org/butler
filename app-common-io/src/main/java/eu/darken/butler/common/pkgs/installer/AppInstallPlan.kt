package eu.darken.butler.common.pkgs.installer

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo

/**
 * Everything [AppInstaller] needs to install one container, resolved by [AppInstallInspector].
 *
 * Every APK the container declares is in [splits]; there is deliberately no device-side split
 * filtering. A sideloaded app has no split-fetch path, so dropping a language or density split
 * breaks it permanently, while both `pm` and `PackageInstaller` accept a full config-split set at
 * the cost of storage alone. The one case that would force a choice - mutually exclusive variants -
 * is rejected during inspection instead.
 */
data class AppInstallPlan(
    val source: APath<*>,
    val format: AppInstallFormat,
    /** From the base APK's own manifest, never from a container-supplied manifest. */
    val pkgId: Pkg.Id?,
    val baseInfo: ApkArchiveInfo?,
    /** Every APK the container declares, base first. */
    val splits: List<Split>,
    /** XAPK only. */
    val obbEntries: List<ObbEntry>,
    val warnings: List<Warning>,
) {

    val totalBytes: Long get() = splits.sumOf { it.size }

    /**
     * [entryPath] addresses the entry inside [source] by its sanitized path segments joined with
     * `/`; for a plain APK it is the file's own name and unused.
     *
     * [stagedName] is generated rather than taken from the archive: split names cross both a shell
     * command boundary and a filesystem boundary, and neither is a place for untrusted strings.
     */
    data class Split(
        val entryPath: String,
        val stagedName: String,
        val size: Long,
    )

    /** [fileName] is a sanitized plain basename; the destination directory is never archive-derived. */
    data class ObbEntry(
        val entryPath: String,
        val fileName: String,
        val size: Long,
    )

    enum class Warning {
        /** The container carries expansion files that will be placed after a successful install. */
        OBB_PRESENT,

        /** No usable container manifest, so the contents were resolved by file name alone. */
        NO_MANIFEST,

        /**
         * Expansion files are present but no elevated access is available. On Android 11+ nothing
         * unprivileged - MANAGE_EXTERNAL_STORAGE included - may write another package's obb
         * directory, so such an install always ends success-with-warning.
         */
        OBB_NEEDS_ELEVATION,
    }
}

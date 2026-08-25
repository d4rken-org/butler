package eu.darken.butler.common.pkgs.installer

import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.io.R

/**
 * Base for everything that can go wrong installing an app-install container.
 *
 * These live here rather than in a workspace module because both the Explorer and the Viewer raise
 * them, and a layer-2 class cannot reach either module's resources.
 */
sealed class AppInstallException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause), HasLocalizedError

/** The bundle is encrypted. Not a user password prompt: APKM protection is not ours to unlock. */
class AppInstallProtectedBundleException(
    val source: APath<*>,
    cause: Throwable? = null,
) : AppInstallException("Protected app bundle: $source", cause) {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.app_install_error_protected_bundle_title.toCaString(),
        description = caString { it.getString(R.string.app_install_error_protected_bundle_description, source.name) },
    )
}

/** A bundletool APK set whose variant targeting Butler cannot read. */
class AppInstallUnsupportedApkSetException(
    val source: APath<*>,
) : AppInstallException("Unsupported APK set: $source") {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.app_install_error_unsupported_apk_set_title.toCaString(),
        description = caString { it.getString(R.string.app_install_error_unsupported_apk_set_description, source.name) },
    )
}

/** The container holds no installable APK. */
class AppInstallUnsupportedBundleException(
    val source: APath<*>,
    val reason: String,
) : AppInstallException("Unsupported app bundle ($reason): $source") {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.app_install_error_unsupported_bundle_title.toCaString(),
        description = caString { it.getString(R.string.app_install_error_unsupported_bundle_description, source.name) },
    )
}

/** The install session itself failed. [detail] is what `pm` or `PackageInstaller` reported. */
class AppInstallSessionException(
    val detail: String,
    cause: Throwable? = null,
) : AppInstallException("Install session failed: $detail", cause) {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.app_install_error_session_title.toCaString(),
        description = caString { it.getString(R.string.app_install_error_session_description, detail) },
    )
}

/** Root or ADB was explicitly requested but is not available right now. */
class AppInstallNoElevationException(
    val requestedMode: AppInstaller.Mode,
) : AppInstallException("Install mode $requestedMode is unavailable") {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.app_install_error_no_elevation_title.toCaString(),
        description = R.string.app_install_error_no_elevation_description.toCaString(),
    )
}

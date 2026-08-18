package eu.darken.butler.common.pkgs.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.local.LocalFileMaterializer
import eu.darken.butler.common.funnel.IPCFunnel
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.pkgs.toPkgId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads an APK archive's manifest data, label, icon and signing certificates.
 *
 * Complements [eu.darken.butler.common.pkgs.pkgops.PkgOps.viewArchive], which stays metadata-only by
 * contract: the label and icon on its result belong to *our* package, not to the archive, because
 * they are resolved after the materialized file is gone. This parser resolves both inside the
 * materializer block, where `applicationInfo.sourceDir` still points at a real file.
 */
@Singleton
class ApkArchiveParser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localFileMaterializer: LocalFileMaterializer,
    private val ipcFunnel: IPCFunnel,
    private val dispatcherProvider: DispatcherProvider,
) {

    /**
     * Parses [path] into an [ApkArchiveInfo], or null if the file is not a readable package archive.
     * Set [includeIcon] to false to skip the rasterization when the caller renders no icon.
     */
    suspend fun parseFile(path: APath<*>, includeIcon: Boolean = true): ApkArchiveInfo? = try {
        withContext(dispatcherProvider.IO) {
            localFileMaterializer.useLocalFile(path) { file ->
                if (!file.exists()) return@useLocalFile null

                val packageInfo = ipcFunnel.use {
                    packageManager.getPackageArchiveInfo(file.path, queryFlags())
                } ?: return@useLocalFile null

                // loadLabel()/loadIcon() read the archive through these, and the framework leaves
                // them null for an archive - without them both resolve against our own package.
                packageInfo.applicationInfo?.sourceDir = file.path
                packageInfo.applicationInfo?.publicSourceDir = file.path

                // Each resolved on its own: a malformed label or icon resource degrades that one
                // field to null, it never fails the parse.
                val label = try {
                    packageInfo.applicationInfo?.loadLabel(context.packageManager)?.toString()
                } catch (e: Exception) {
                    log(TAG, WARN) { "Failed to load label for $path: ${e.asLog()}" }
                    null
                }
                val icon = if (includeIcon) {
                    try {
                        packageInfo.applicationInfo
                            ?.let { resolveIconDrawable(it) }
                            ?.let { rasterize(it, DISPLAY_ICON_EDGE_PX) }
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Failed to load icon for $path: ${e.asLog()}" }
                        null
                    }
                } else {
                    null
                }

                map(packageInfo, label = label, icon = icon)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "parseFile($path) failed: ${e.asLog()}" }
        null
    }

    /**
     * Renders the archive's launcher icon at up to [edgePx] per edge, or null if the archive has no
     * readable icon. Separate from [parseFile] because the icon it puts on [ApkArchiveInfo] is sized
     * for a list row - this one is for export and full-size display, so it is only paid for on demand.
     *
     * The rasterization never enlarges the drawable beyond its own intrinsic size, so [edgePx] is a
     * ceiling rather than a target and an archive shipping only a small icon yields a small bitmap.
     */
    suspend fun loadIcon(path: APath<*>, edgePx: Int = EXPORT_ICON_EDGE_PX): Bitmap? = try {
        withContext(dispatcherProvider.IO) {
            localFileMaterializer.useLocalFile(path) { file ->
                if (!file.exists()) return@useLocalFile null

                // No GET_PERMISSIONS/signing flags: the icon needs nothing but the application block.
                val packageInfo = ipcFunnel.use {
                    packageManager.getPackageArchiveInfo(file.path, 0)
                } ?: return@useLocalFile null

                val appInfo = packageInfo.applicationInfo ?: return@useLocalFile null
                appInfo.sourceDir = file.path
                appInfo.publicSourceDir = file.path

                resolveIconDrawable(appInfo)?.let { rasterize(it, edgePx) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "loadIcon($path) failed: ${e.asLog()}" }
        null
    }

    /**
     * The shared [PackageInfo] mapper. Also used for installed packages, where the caller already
     * has a [PackageInfo] and there is no file to parse.
     */
    fun map(packageInfo: PackageInfo, label: String? = null, icon: Bitmap? = null) = ApkArchiveInfo(
        id = packageInfo.packageName.toPkgId(),
        label = label,
        icon = icon,
        versionName = packageInfo.versionName,
        versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
        minSdk = packageInfo.applicationInfo?.minSdkVersion,
        targetSdk = packageInfo.applicationInfo?.targetSdkVersion,
        requestedPermissions = packageInfo.requestedPermissions?.toList() ?: emptyList(),
        signatures = extractSignatures(packageInfo).mapNotNull { toApkSignature(it) },
    )

    internal fun extractSignatures(packageInfo: PackageInfo): List<Signature> = when {
        hasApiLevel(28) -> {
            @Suppress("NewApi")
            packageInfo.signingInfo?.apkContentsSigners?.filterNotNull() ?: emptyList()
        }

        else -> {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.filterNotNull() ?: emptyList()
        }
    }

    internal fun toApkSignature(signature: Signature): ApkSignature? = try {
        val bytes = signature.toByteArray()
        val subjectDn = try {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
            cert.subjectDN.name
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to parse signing certificate: ${e.asLog()}" }
            null
        }
        ApkSignature(
            subjectDn = subjectDn,
            sha256 = formatFingerprint(MessageDigest.getInstance("SHA-256").digest(bytes)),
        )
    } catch (e: Exception) {
        log(TAG, WARN) { "Failed to read signature: ${e.asLog()}" }
        null
    }

    internal fun formatFingerprint(bytes: ByteArray): String = bytes.joinToString(":") {
        "%02X".format(it)
    }

    /**
     * Flags a [PackageInfo] has to be queried with so [map] sees permissions and signing
     * certificates - the signing flag differs by API level.
     */
    fun queryFlags(): Int = PackageManager.GET_PERMISSIONS or signingFlag()

    private fun signingFlag(): Int = when {
        hasApiLevel(28) -> {
            @Suppress("NewApi")
            PackageManager.GET_SIGNING_CERTIFICATES
        }

        else -> {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    /**
     * The archive's own icon, or null if it declares none.
     *
     * Two things this deliberately does not do. It never falls back to
     * [android.content.pm.ApplicationInfo.loadIcon] when `icon == 0`: that hands back the framework's
     * generic application icon, which would then be shown and exported as though the APK contained
     * it. And it resolves through the archive's own [android.content.res.Resources] rather than
     * `loadIcon()`, because `loadIcon()` picks the bucket for the *device's* density - a low-density
     * screen would otherwise cap an export well below what the archive actually ships.
     *
     * The density request is a preference, not a guarantee: an archive that ships nothing above
     * hdpi returns its hdpi asset, which the framework may then scale up for us.
     */
    internal fun resolveIconDrawable(appInfo: ApplicationInfo): Drawable? {
        if (appInfo.icon == 0) {
            log(TAG) { "Archive declares no icon resource" }
            return null
        }
        return try {
            context.packageManager.getResourcesForApplication(appInfo)
                .getDrawableForDensity(appInfo.icon, DisplayMetrics.DENSITY_XXXHIGH, null)
        } catch (e: Exception) {
            log(TAG, WARN) { "High-density icon lookup failed, falling back: ${e.asLog()}" }
            // Still scoped to the declared resource, so this cannot become the framework default.
            try {
                context.packageManager.getResourcesForApplication(appInfo).getDrawable(appInfo.icon, null)
            } catch (e2: Exception) {
                log(TAG, WARN) { "Icon resource $appInfo could not be loaded: ${e2.asLog()}" }
                null
            }
        }
    }

    private fun rasterize(drawable: Drawable, edgePx: Int): Bitmap? {
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: edgePx
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: edgePx
        val scale = minOf(edgePx.toFloat() / w, edgePx.toFloat() / h, 1f)
        val outW = (w * scale).toInt().coerceIn(1, edgePx)
        val outH = (h * scale).toInt().coerceIn(1, edgePx)
        return try {
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, outW, outH)
            drawable.draw(Canvas(bitmap))
            bitmap
        } catch (e: OutOfMemoryError) {
            log(TAG, WARN) { "rasterize OOM at ${outW}x$outH" }
            null
        }
    }

    companion object {
        private val TAG = logTag("Pkg", "Apk", "Parser")

        /** List-row sized, cheap enough to keep on every parsed [ApkArchiveInfo]. */
        private const val DISPLAY_ICON_EDGE_PX = 256

        /** Full-size display and export, loaded on demand only. */
        const val EXPORT_ICON_EDGE_PX = 512
    }
}

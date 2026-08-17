package eu.darken.butler.common.pkgs.apk

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
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
                        packageInfo.applicationInfo?.loadIcon(context.packageManager)?.let { rasterize(it) }
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

    private fun rasterize(drawable: Drawable): Bitmap? {
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_EDGE_PX
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_EDGE_PX
        val scale = minOf(ICON_EDGE_PX.toFloat() / w, ICON_EDGE_PX.toFloat() / h, 1f)
        val outW = (w * scale).toInt().coerceIn(1, ICON_EDGE_PX)
        val outH = (h * scale).toInt().coerceIn(1, ICON_EDGE_PX)
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
        private const val ICON_EDGE_PX = 256
    }
}

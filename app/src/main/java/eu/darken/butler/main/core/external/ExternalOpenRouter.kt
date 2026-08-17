package eu.darken.butler.main.core.external

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.storage.DocumentUriResolver
import java.io.File
import java.util.Locale
import javax.inject.Inject

/**
 * Turns the URI of an inbound ACTION_VIEW intent into something Butler may safely open.
 *
 * MainActivity is exported and an explicit intent bypasses the manifest filters entirely, so the
 * URI is attacker-controlled: without validation a caller could point Butler at app-private files,
 * either directly via `file://` or through Butler's own FileProvider, whose `device_root` mapping
 * covers the whole filesystem.
 */
@Reusable
class ExternalOpenRouter internal constructor(
    private val context: Context,
    private val documentUriResolver: DocumentUriResolver,
    private val importer: ExternalContentImporter,
    private val privatePathPrefixes: Set<String>,
) {

    @Inject constructor(
        @ApplicationContext context: Context,
        documentUriResolver: DocumentUriResolver,
        importer: ExternalContentImporter,
    ) : this(context, documentUriResolver, importer, PRIVATE_PATH_PREFIXES)

    /**
     * Validates the inbound URI and classifies it. Returns null for anything Butler must not touch.
     */
    fun sanitize(uri: Uri): SourceRef? = when (uri.scheme?.lowercase(Locale.ROOT)) {
        ContentResolver.SCHEME_FILE -> {
            val path = uri.path
            if (path == null) {
                log(TAG, WARN) { "file URI without a path: $uri" }
                null
            } else {
                toLocalRef(File(path))
            }
        }

        ContentResolver.SCHEME_CONTENT -> {
            // Android accepts `content://<userId>@<authority>/...`, so the raw authority can carry a
            // user prefix that would hide our own providers from the checks below.
            when (uri.authority?.replaceFirst(USER_PREFIX, "")) {
                "${context.packageName}.provider" -> fromOwnProvider(uri)
                "${context.packageName}.provider.documents" -> {
                    // Our own DocumentsProvider decodes document IDs to arbitrary paths and its
                    // MANAGE_DOCUMENTS protection doesn't apply to us, so it is not a valid source.
                    log(TAG, WARN) { "Refusing our own documents provider: $uri" }
                    null
                }

                else -> SourceRef.Content(uri)
            }
        }

        else -> {
            log(TAG, WARN) { "Refusing unsupported scheme: $uri" }
            null
        }
    }

    /**
     * Butler's own FileProvider exposes the whole device via the `device_root` root, so a URI
     * pointing back at us is reverse-mapped to the file it stands for and then held to the same
     * private-path rules as any other local path.
     */
    private fun fromOwnProvider(uri: Uri): SourceRef? {
        val segments = uri.pathSegments
        if (segments.firstOrNull() != DEVICE_ROOT_SEGMENT) {
            log(TAG, WARN) { "Refusing own-provider URI outside $DEVICE_ROOT_SEGMENT: $uri" }
            return null
        }
        return toLocalRef(File("/" + segments.drop(1).joinToString("/")))
    }

    private fun toLocalRef(file: File): SourceRef? {
        val canonical = try {
            file.canonicalFile
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to canonicalize $file: ${e.asLog()}" }
            return null
        }
        if (privatePathPrefixes.any { canonical.path.startsWith(it) }) {
            log(TAG, WARN) { "Refusing private path: $canonical" }
            return null
        }
        if (!canonical.isAbsolute) {
            log(TAG, WARN) { "Refusing non-absolute path: $canonical" }
            return null
        }
        return SourceRef.Local(LocalPath.build(canonical))
    }

    /**
     * Picks the most trustworthy type: what the caller declared, what the provider reports, and
     * finally the file name. Generic types (wildcard, octet-stream) say nothing and fall through.
     */
    fun resolveMime(
        intentType: String?,
        resolverType: String?,
        displayName: String,
    ): MimeInfo {
        normalizeType(intentType)?.let { return MimeInfo(it) }
        normalizeType(resolverType)?.let { return MimeInfo(it) }
        return MimeInfo.fromFileName(displayName)
    }

    private fun normalizeType(raw: String?): String? {
        val cleaned = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        // A subtype wildcard like `image/*` looks concrete but yields no extension, which would
        // leave an imported file extensionless and unopenable for the Viewer.
        if (cleaned.isBlank() || cleaned.endsWith(WILDCARD_SUBTYPE) || cleaned == GENERIC_BINARY_TYPE) return null
        return cleaned
    }

    /**
     * Resolves the content to a real local file the Viewer can open, importing it into the cache
     * when there is no directly accessible path. Returns null when that isn't possible.
     */
    suspend fun resolveForView(
        ref: SourceRef,
        mime: MimeInfo,
        displayName: String,
    ): LocalPath? = when (ref) {
        is SourceRef.Local -> viewablePath(ref.path, mime, displayName)
        is SourceRef.Content -> {
            // The resolver concatenates the document ID's sub-path without canonicalizing it, so its
            // output is held to the same private-path rules as any other local path. A path we may
            // use but can't read (no storage permission) still works through the provider's stream.
            val resolved = documentUriResolver.resolve(ref.uri)
                ?.let { toLocalRef(it.file) as? SourceRef.Local }?.path
            when {
                resolved != null && resolved.file.canRead() -> viewablePath(resolved, mime, displayName)
                else -> importer.importToCache(ref.uri, displayName, mime)
            }
        }
    }

    /**
     * The Viewer picks its decoder by file extension, so content whose type says it can be shown but
     * whose name doesn't is copied to a cache file that carries a MIME-derived extension.
     */
    private suspend fun viewablePath(path: LocalPath, mime: MimeInfo, displayName: String): LocalPath? = when {
        mime.isViewable && !mime.hasMatchingViewableExtension(path.name) -> {
            importer.importToCache(Uri.fromFile(path.file), displayName, mime)
        }

        else -> path
    }

    companion object {
        private val TAG = logTag("Main", "ExternalOpen", "Router")
        private const val DEVICE_ROOT_SEGMENT = "device_root"
        private const val WILDCARD_SUBTYPE = "/*"
        private const val GENERIC_BINARY_TYPE = "application/octet-stream"
        private val USER_PREFIX = Regex("^\\d+@")

        /**
         * Everything app-private lives below these. Paths below `/sdcard` and `/storage` are
         * unaffected: they canonicalize to a mount point outside `/data`, not into `/data/media`.
         */
        internal val PRIVATE_PATH_PREFIXES = setOf("/data/", "/proc/")
    }
}

/**
 * What Butler ended up with after validating an inbound URI.
 */
sealed interface SourceRef {
    /** Content only reachable through a ContentProvider. */
    data class Content(val uri: Uri) : SourceRef

    /** A real file on the device that Butler may access directly. */
    data class Local(val path: LocalPath) : SourceRef
}

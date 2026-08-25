package eu.darken.butler.common.pkgs.installer

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.archive.ArchiveEntryMeta
import eu.darken.butler.common.files.archive.ArchiveIndex
import eu.darken.butler.common.files.archive.ArchivePasswordRequiredException
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.canUseRootNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves an app-install container into the [AppInstallPlan] that describes how to install it.
 *
 * Container-supplied names are untrusted: they cross a shell command boundary and a filesystem
 * boundary on the way to `pm` and to the obb directory. Nothing an archive declares is used as a
 * file name - staged names are generated here, and expansion destinations are validated against the
 * package the base APK itself declares.
 */
@Singleton
class AppInstallInspector @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val archiveService: ArchiveService,
    private val apkArchiveParser: ApkArchiveParser,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val dispatcherProvider: DispatcherProvider,
) {

    suspend fun inspect(path: APath<*>): AppInstallPlan = withContext(dispatcherProvider.IO) {
        val format = AppInstallFormat.fromFileName(path.name)
            ?: throw AppInstallUnsupportedBundleException(path, "not an app install container")
        log(TAG) { "inspect($path): format=$format" }

        when (format) {
            AppInstallFormat.APK -> inspectApk(path)
            else -> inspectBundle(path, format)
        }
    }

    private suspend fun inspectApk(path: APath<*>): AppInstallPlan {
        val size = gatewaySwitch.lookup(path, LookupOptions.BASE).size ?: 0L
        val baseInfo = apkArchiveParser.parseFile(path)
        return AppInstallPlan(
            source = path,
            format = AppInstallFormat.APK,
            pkgId = baseInfo?.id,
            baseInfo = baseInfo,
            splits = listOf(
                AppInstallPlan.Split(entryPath = path.name, stagedName = BASE_APK_NAME, size = size),
            ),
            obbEntries = emptyList(),
            warnings = emptyList(),
        )
    }

    private suspend fun inspectBundle(path: APath<*>, format: AppInstallFormat): AppInstallPlan {
        val index = indexOrFail(path, format)
        if (index.isEncrypted) throw AppInstallProtectedBundleException(path)
        if (index.entriesBySegments.size > MAX_INDEX_ENTRIES) {
            throw AppInstallUnsupportedBundleException(path, "index holds ${index.entriesBySegments.size} entries")
        }

        val rootApks = index.entriesBySegments.values
            .filter { !it.isDirectory && !it.isSymlink }
            .filter { it.segments.size == 1 && it.segments.single().endsWith(APK_SUFFIX, ignoreCase = true) }
            .filter { isUsableSize(it) }

        if (format == AppInstallFormat.APKS) rejectApkSets(path, index, rootApks)
        if (rootApks.isEmpty()) throw AppInstallUnsupportedBundleException(path, "no APK entries")
        if (rootApks.size > MAX_SPLITS) {
            throw AppInstallUnsupportedBundleException(path, "declares ${rootApks.size} APKs")
        }

        val manifest = readManifest(path, format)
        val baseEntry = pickBase(rootApks, manifest)
        val ordered = listOf(baseEntry) + rootApks
            .filter { it !== baseEntry }
            .sortedBy { it.segments.single().lowercase() }

        // Running sum only so a declared-size set that cannot be added up is caught here rather
        // than wrapping around into a bogus `pm install-create -S` total.
        var total = 0L
        val splits = ordered.mapIndexed { position, entry ->
            val size = entry.size ?: 0L
            total = try {
                Math.addExact(total, size)
            } catch (e: ArithmeticException) {
                throw AppInstallUnsupportedBundleException(path, "declared sizes overflow")
            }
            AppInstallPlan.Split(
                entryPath = entry.segments.joinToString("/"),
                stagedName = if (position == 0) BASE_APK_NAME else "split_%04d.apk".format(position),
                size = size,
            )
        }

        val baseInfo = apkArchiveParser.parseFile(ArchivePath(container = path, segments = baseEntry.segments))
        val obbEntries = if (format == AppInstallFormat.XAPK) {
            collectObbEntries(index, manifest, baseInfo?.id?.name)
        } else {
            emptyList()
        }
        val elevated = hasElevation()

        val warnings = buildList {
            if (obbEntries.isNotEmpty()) {
                add(AppInstallPlan.Warning.OBB_PRESENT)
                if (!elevated) add(AppInstallPlan.Warning.OBB_NEEDS_ELEVATION)
            }
            if (format == AppInstallFormat.XAPK && manifest == null) add(AppInstallPlan.Warning.NO_MANIFEST)
        }

        log(TAG, INFO) {
            "inspect($path): ${splits.size} split(s), ${obbEntries.size} expansion(s), warnings=$warnings"
        }
        return AppInstallPlan(
            source = path,
            format = format,
            pkgId = baseInfo?.id,
            baseInfo = baseInfo,
            splits = splits,
            obbEntries = obbEntries,
            warnings = warnings,
        )
    }

    private suspend fun indexOrFail(path: APath<*>, format: AppInstallFormat): ArchiveIndex = try {
        archiveService.index(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ArchivePasswordRequiredException) {
        throw AppInstallProtectedBundleException(path, e)
    } catch (e: Exception) {
        // A protected APKM is a whole-file wrapper: it does not even index, and the corruption error
        // that surfaces would otherwise be shown as a damaged file.
        if (format == AppInstallFormat.APKM) throw AppInstallProtectedBundleException(path, e) else throw e
    }

    /**
     * A bundletool APK set stores its variant targeting in `toc.pb`, which Butler cannot read, so
     * there is no way to tell which of its mutually exclusive variants fits this device.
     */
    private fun rejectApkSets(path: APath<*>, index: ArchiveIndex, rootApks: List<ArchiveEntryMeta>) {
        val hasToc = index.entriesBySegments.keys.any {
            it.size == 1 && it.single().equals("toc.pb", ignoreCase = true)
        }
        val hasVariantDirs = index.entriesBySegments.keys.any {
            it.isNotEmpty() && (it.first().equals("splits", true) || it.first().equals("standalones", true))
        }
        if (hasToc || hasVariantDirs) throw AppInstallUnsupportedApkSetException(path)

        val standalones = rootApks.filter { it.segments.single().startsWith("standalone-", ignoreCase = true) }
        if (standalones.size > 1 && standalones.size == rootApks.size) throw AppInstallUnsupportedApkSetException(path)
    }

    private fun pickBase(apks: List<ArchiveEntryMeta>, manifest: BundleManifest?): ArchiveEntryMeta {
        fun byName(name: String?) = name
            ?.substringAfterLast('/')
            ?.let { wanted -> apks.firstOrNull { it.segments.single().equals(wanted, ignoreCase = true) } }

        byName(manifest?.baseFile)?.let { return it }
        byName(BASE_APK_NAME)?.let { return it }
        byName(manifest?.packageName?.let { "$it$APK_SUFFIX" })?.let { return it }

        val nonConfig = apks.filterNot { isConfigSplitName(it.segments.single()) }
        if (nonConfig.size == 1) return nonConfig.single()
        val candidates = nonConfig.ifEmpty { apks }
        return candidates.maxByOrNull { it.size ?: 0L } ?: candidates.first()
    }

    private fun isConfigSplitName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("split_") || lower.startsWith("config.")
    }

    /**
     * Expansion files, taken from the entries under `Android/obb` in the archive plus anything the
     * manifest points at. Both are only candidates: an entry survives only if it names a plain file
     * directly under the package directory of the package the base APK declares.
     */
    private fun collectObbEntries(
        index: ArchiveIndex,
        manifest: BundleManifest?,
        basePackageName: String?,
    ): List<AppInstallPlan.ObbEntry> {
        if (basePackageName == null) return emptyList()

        val declared = manifest?.expansionPaths.orEmpty()
            .mapNotNull { raw -> index.entriesBySegments.values.firstOrNull { it.rawName == raw } }
        val scanned = index.entriesBySegments.values.filter {
            it.segments.size >= 2 && it.segments[0].equals("Android", true) && it.segments[1].equals("obb", true)
        }

        return (scanned + declared)
            .filter { !it.isDirectory && !it.isSymlink }
            .filter { isUsableSize(it) }
            .filter { entry ->
                // Exactly Android/obb/<package>/<file>: anything deeper or shallower is not a
                // destination we would build, and a foreign package is not ours to write into.
                val ok = entry.segments.size == 4 && entry.segments[2] == basePackageName
                if (!ok) log(TAG, WARN) { "Dropping expansion entry ${entry.rawName}" }
                ok
            }
            .distinctBy { it.segments[3] }
            .take(MAX_OBB_ENTRIES)
            .map {
                AppInstallPlan.ObbEntry(
                    entryPath = it.segments.joinToString("/"),
                    fileName = it.segments[3],
                    size = it.size ?: 0L,
                )
            }
    }

    internal fun isUsableSize(entry: ArchiveEntryMeta): Boolean {
        val size = entry.size
        if (size == null || size < 0L) {
            log(TAG, WARN) { "Dropping entry with unusable size: ${entry.rawName} ($size)" }
            return false
        }
        return true
    }

    private suspend fun hasElevation(): Boolean = rootManager.canUseRootNow() || adbManager.canUseAdbNow()

    private suspend fun readManifest(path: APath<*>, format: AppInstallFormat): BundleManifest? {
        val entryName = when (format) {
            AppInstallFormat.XAPK -> "manifest.json"
            AppInstallFormat.APKM -> "info.json"
            else -> return null
        }
        val raw = try {
            archiveService
                .openEntryStream(ArchivePath(container = path, segments = listOf(entryName)))
                .use { readCapped(it, MAX_MANIFEST_BYTES) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArchivePasswordRequiredException) {
            // An encrypted zip still lists its entry names, so a readable listing proves nothing.
            throw AppInstallProtectedBundleException(path, e)
        } catch (e: Exception) {
            log(TAG, WARN) { "No readable $entryName in $path: ${e.asLog()}" }
            return null
        }

        return try {
            val root = JSON.parseToJsonElement(raw) as? JsonObject ?: return null
            when (format) {
                AppInstallFormat.XAPK -> BundleManifest(
                    packageName = root.string("package_name"),
                    baseFile = root.splitApks().firstOrNull { it.second == "base" }?.first,
                    expansionPaths = root.expansionPaths(),
                )

                else -> BundleManifest(packageName = root.string("pname"))
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Unparseable $entryName in $path: ${e.asLog()}" }
            null
        }
    }

    /**
     * Reads at most [limit] bytes as UTF-8. The declared size of an archive entry says nothing about
     * how much it actually decompresses to, so the manifest read cannot be sized from metadata.
     */
    private fun readCapped(stream: InputStream, limit: Int): String {
        val buffer = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            val read = stream.read(buffer, filled, limit - filled)
            if (read == -1) break
            filled += read
        }
        return String(buffer, 0, filled, Charsets.UTF_8)
    }

    /**
     * The parts of a container manifest Butler acts on. Every field is a hint: producers disagree on
     * spelling and on whether numbers are encoded as strings, and none of it is trusted for naming.
     */
    private data class BundleManifest(
        val packageName: String? = null,
        val baseFile: String? = null,
        val expansionPaths: List<String> = emptyList(),
    )

    /** Reads a field that producers encode as either a JSON string or a bare number. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.splitApks(): List<Pair<String, String?>> =
        (this["split_apks"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { entry -> entry.string("file")?.let { it to entry.string("id") } }
            ?: emptyList()

    private fun JsonObject.expansionPaths(): List<String> =
        (this["expansions"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it.string("install_path") ?: it.string("file") ?: it.string("install_location") }
            ?: emptyList()

    companion object {
        private val TAG = logTag("Pkg", "Installer", "Inspector")
        private const val APK_SUFFIX = ".apk"
        private const val BASE_APK_NAME = "base.apk"
        private const val MAX_INDEX_ENTRIES = 10_000
        private const val MAX_SPLITS = 256
        private const val MAX_OBB_ENTRIES = 64
        private const val MAX_MANIFEST_BYTES = 512 * 1024
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

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
import eu.darken.butler.common.files.archive.ArchiveEntrySafety
import eu.darken.butler.common.files.archive.ArchiveIndex
import eu.darken.butler.common.files.archive.ArchivePasswordRequiredException
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.extensions.Segments
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
        val index = indexOrFail(path)
        if (index.isEncrypted) throw AppInstallProtectedBundleException(path)
        if (index.skippedUnsafe != 0) {
            throw AppInstallUnsupportedBundleException(path, "${index.skippedUnsafe} entries failed the name policy")
        }
        if (index.entriesBySegments.size > MAX_INDEX_ENTRIES) {
            throw AppInstallUnsupportedBundleException(path, "index holds ${index.entriesBySegments.size} entries")
        }

        val rootApks = index.entriesBySegments.values
            .filter { !it.isDirectory }
            .filter { it.segments.size == 1 && it.segments.single().endsWith(APK_SUFFIX, ignoreCase = true) }

        if (format == AppInstallFormat.APKS) rejectApkSets(path, index, rootApks)

        val manifest = readManifest(path, format)
        val ordered = resolveManifestSplits(path, index, format, manifest) ?: run {
            if (rootApks.isEmpty()) throw AppInstallUnsupportedBundleException(path, "no APK entries")
            val baseEntry = pickBase(rootApks, manifest)
            listOf(baseEntry) + rootApks
                .filter { it !== baseEntry }
                .sortedBy { it.segments.single().lowercase() }
        }
        if (ordered.size > MAX_SPLITS) {
            throw AppInstallUnsupportedBundleException(path, "declares ${ordered.size} APKs")
        }
        ordered.forEach { requireInstallable(path, it) }

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

        val baseInfo = apkArchiveParser.parseFile(ArchivePath(container = path, segments = ordered.first().segments))
        val obbEntries = if (format == AppInstallFormat.XAPK) {
            collectObbEntries(path, index, manifest, baseInfo?.id?.name)
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
            indexEntryCount = index.entriesBySegments.size,
        )
    }

    /**
     * Only a password demand marks a container as protected. A truncated download, a non-seekable
     * provider and a protected APKM all surface as the same read error, so mapping by file extension
     * would send most of those users after a recovery that does not apply to them.
     */
    private suspend fun indexOrFail(path: APath<*>): ArchiveIndex = try {
        archiveService.index(path)
    } catch (e: ArchivePasswordRequiredException) {
        throw AppInstallProtectedBundleException(path, e)
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

    /**
     * Every split an XAPK manifest declares, the base one first, or null when there is nothing to
     * go by and the root-level scan decides instead.
     *
     * Matching is on the exact raw entry name, so a split stored in a subdirectory - which the
     * root-level scan never sees - is included. A declared split that is not in the container fails
     * the bundle: installing the remainder produces an app that is missing part of itself.
     */
    private fun resolveManifestSplits(
        path: APath<*>,
        index: ArchiveIndex,
        format: AppInstallFormat,
        manifest: BundleManifest?,
    ): List<ArchiveEntryMeta>? {
        if (format != AppInstallFormat.XAPK) return null
        val declared = manifest?.splits?.takeIf { it.isNotEmpty() } ?: return null

        val byRawName = index.entriesBySegments.values.filter { !it.isDirectory }.associateBy { it.rawName }
        return declared
            .sortedBy { if (it.id == BASE_SPLIT_ID) 0 else 1 }
            .map { split ->
                byRawName[split.file]
                    ?: throw AppInstallUnsupportedBundleException(path, "declared split ${split.file} is missing")
            }
    }

    /**
     * A container that cannot be installed as declared is refused rather than repaired: silently
     * dropping a part Butler cannot read installs an app that only looks complete.
     */
    private fun requireInstallable(path: APath<*>, entry: ArchiveEntryMeta) {
        if (entry.isSymlink) {
            throw AppInstallUnsupportedBundleException(path, "entry ${entry.rawName} is a symlink")
        }
        if (!isUsableSize(entry)) {
            throw AppInstallUnsupportedBundleException(path, "entry ${entry.rawName} has no usable size")
        }
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
     * Expansion files: what the manifest declares plus what the archive already stores under
     * `Android/obb`. A candidate survives only if its destination is a plain file directly under
     * the directory of the package the base APK declares, and two candidates that would land on the
     * same name fail the bundle instead of overwriting each other.
     */
    private fun collectObbEntries(
        path: APath<*>,
        index: ArchiveIndex,
        manifest: BundleManifest?,
        basePackageName: String?,
    ): List<AppInstallPlan.ObbEntry> {
        if (basePackageName == null) {
            // Without it there is no destination to build, and dropping the declaration instead
            // would report an install of an app that is missing its expansion data as a success.
            if (manifest?.expansions?.isNotEmpty() == true) {
                throw AppInstallUnsupportedBundleException(path, "expansions are declared but the base package is not")
            }
            return emptyList()
        }

        val byRawName = index.entriesBySegments.values.filter { !it.isDirectory }.associateBy { it.rawName }
        // The manifest is the only place that says where an expansion has to land when the archive
        // does not already store it in its destination shape, so it is consulted first.
        val declared = manifest?.expansions.orEmpty().map { expansion ->
            val entry = byRawName[expansion.sourceFile]
                ?: throw AppInstallUnsupportedBundleException(
                    path,
                    "declared expansion ${expansion.sourceFile} is missing",
                )
            val destination = obbDestinationName(expansion, basePackageName)
                ?: throw AppInstallUnsupportedBundleException(
                    path,
                    "declared expansion ${expansion.sourceFile} has no usable destination",
                )
            entry to destination
        }
        val scanned = index.entriesBySegments.values
            .filter { !it.isDirectory }
            .filter { it.segments.size >= 2 && it.segments[0].equals("Android", true) }
            .filter { it.segments[1].equals("obb", true) }
            .mapNotNull { entry ->
                // Exactly Android/obb/<package>/<file>: anything deeper or shallower is not a
                // destination we would build, and a foreign package is not ours to write into.
                val ok = entry.segments.size == 4 && entry.segments[2] == basePackageName
                if (!ok) log(TAG, WARN) { "Dropping expansion entry ${entry.rawName}" }
                if (ok) entry to entry.segments[3] else null
            }

        val candidates = LinkedHashMap<Segments, Pair<ArchiveEntryMeta, String>>()
        (declared + scanned).forEach { candidate -> candidates.getOrPut(candidate.first.segments) { candidate } }
        if (candidates.size > MAX_OBB_ENTRIES) {
            throw AppInstallUnsupportedBundleException(path, "declares ${candidates.size} expansions")
        }
        val collisions = candidates.values.groupBy { it.second.lowercase() }.filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            throw AppInstallUnsupportedBundleException(path, "expansions collide on ${collisions.keys.first()}")
        }

        return candidates.values.map { (entry, destination) ->
            requireInstallable(path, entry)
            AppInstallPlan.ObbEntry(
                entryPath = entry.segments.joinToString("/"),
                fileName = destination,
                size = entry.size ?: 0L,
            )
        }
    }

    /**
     * The basename a declared expansion may be written under, or null when it points anywhere Butler
     * would not write.
     *
     * With an `install_path` that has to be `Android/obb/<base package>/<plain name>`, since that is
     * the only destination shape built here. Producers that leave it out say where the payload goes
     * with `install_location` instead:
     *
     *     {"file": "main.123.pkg.obb", "install_location": "EXTERNAL_STORAGE"}
     *
     * which lands under the base package's own obb directory, under the source's plain basename.
     */
    private fun obbDestinationName(expansion: ManifestExpansion, basePackageName: String): String? {
        val installPath = expansion.installPath
            ?: return when {
                expansion.installLocation.equals(EXTERNAL_STORAGE, ignoreCase = true) ->
                    ArchiveEntrySafety.parseEntryName(expansion.sourceFile)?.lastOrNull()

                else -> null
            }
        val segments = ArchiveEntrySafety.parseEntryName(installPath) ?: return null
        if (segments.size != 4) return null
        if (!segments[0].equals("Android", true) || !segments[1].equals("obb", true)) return null
        if (segments[2] != basePackageName) return null
        return segments[3]
    }

    internal fun isUsableSize(entry: ArchiveEntryMeta): Boolean {
        val size = entry.size
        if (size == null || size < 0L) {
            log(TAG, WARN) { "Unusable size on ${entry.rawName} ($size)" }
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
                    splits = root.splitApks(),
                    expansions = root.expansions(path),
                )

                else -> BundleManifest(packageName = root.string("pname"))
            }
        } catch (e: AppInstallUnsupportedBundleException) {
            // A manifest Butler cannot read at all is one to fall back from; one that declares
            // something unreadable is a container that must not be installed as if it had not.
            throw e
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
        val splits: List<ManifestSplit> = emptyList(),
        val expansions: List<ManifestExpansion> = emptyList(),
    ) {
        val baseFile: String? get() = splits.firstOrNull { it.id == BASE_SPLIT_ID }?.file
    }

    /** [file] is an archive entry name, [id] the split name the producer gave it. */
    private data class ManifestSplit(
        val file: String,
        val id: String?,
    )

    /**
     * [sourceFile] is an archive entry name. [installPath] is where the producer wants it placed;
     * when that is absent [installLocation] names the storage it belongs on instead.
     */
    private data class ManifestExpansion(
        val sourceFile: String,
        val installPath: String?,
        val installLocation: String?,
    )

    /** Reads a field that producers encode as either a JSON string or a bare number. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.splitApks(): List<ManifestSplit> =
        (this["split_apks"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { entry -> entry.string("file")?.let { ManifestSplit(file = it, id = entry.string("id")) } }
            ?: emptyList()

    /**
     * Every expansion the manifest declares, or none when it declares none.
     *
     * A declaration that cannot be read is not the same as no declaration: dropping it installs an
     * app that is missing its expansion data and reports that as an unqualified success, which is
     * exactly what the checks further down refuse to do.
     */
    private fun JsonObject.expansions(path: APath<*>): List<ManifestExpansion> {
        fun reject(reason: String): Nothing = throw AppInstallUnsupportedBundleException(path, reason)

        val declared = this["expansions"] ?: return emptyList()
        val entries = declared as? JsonArray ?: reject("expansions are not a list")
        return entries.map { element ->
            val entry = element as? JsonObject ?: reject("an expansion declaration is not an object")
            val source = entry.string("file")
                ?: entry.string("install_path")
                ?: reject("an expansion declaration names no file")
            ManifestExpansion(
                sourceFile = source,
                installPath = entry.string("install_path"),
                installLocation = entry.string("install_location"),
            )
        }
    }

    companion object {
        private val TAG = logTag("Pkg", "Installer", "Inspector")
        private const val APK_SUFFIX = ".apk"
        private const val BASE_APK_NAME = "base.apk"
        private const val BASE_SPLIT_ID = "base"
        private const val EXTERNAL_STORAGE = "EXTERNAL_STORAGE"
        private const val MAX_INDEX_ENTRIES = 10_000
        private const val MAX_SPLITS = 256
        private const val MAX_OBB_ENTRIES = 64
        private const val MAX_MANIFEST_BYTES = 512 * 1024
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

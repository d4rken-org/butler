package eu.darken.butler.common.pkgs.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.archive.ArchiveIndex
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.errors.ServiceConnectionLostException
import eu.darken.butler.common.files.extensions.isChildOf
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootUnavailableException
import eu.darken.butler.common.root.canUseRootNow
import eu.darken.butler.common.shell.ShellOps
import eu.darken.butler.common.shell.ShellOpsException
import eu.darken.butler.common.shell.ipc.ShellOpsCmd
import eu.darken.butler.common.shell.ipc.ShellOpsResult
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.user.UserManager2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.buffer
import okio.use
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * Installs an [AppInstallPlan], preferring a silent elevated install and falling back to the
 * platform installer, which shows Android's own confirmation dialog.
 */
@Singleton
class AppInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellOps: ShellOps,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val userManager2: UserManager2,
    private val gatewaySwitch: GatewaySwitch,
    private val archiveService: ArchiveService,
    private val apkArchiveParser: ApkArchiveParser,
    private val storageEnvironment: StorageEnvironment,
    private val statusRelay: AppInstallStatusRelay,
    private val dispatcherProvider: DispatcherProvider,
) {

    enum class Mode { AUTO, ROOT, ADB, SYSTEM }

    private val sweepMutex = Mutex()
    private val localSwept = AtomicBoolean(false)
    private val shellSwept = AtomicBoolean(false)

    /** Expansion partials some install is filling right now, so no sweep mistakes them for scratch. */
    private val activeObbPartials: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Staging directory names some install is still using, for the same reason. */
    private val activeStagingNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun hasElevation(): Boolean = rootManager.canUseRootNow() || adbManager.canUseAdbNow()

    /** False while Butler is not an authorized install source; [unknownSourcesSettings] fixes that. */
    fun canUseSystemInstaller(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Runs [plan], reporting progress and exactly one terminal event.
     *
     * Falling through to the next mode happens only when the transport itself is unavailable or
     * broken. A semantic install failure - an invalid APK, a signature conflict, a downgrade, an
     * incompatible SDK or ABI - is terminal: re-showing a system confirm dialog after root already
     * proved the APK unusable would be misleading.
     */
    fun install(plan: AppInstallPlan, mode: Mode = Mode.AUTO): Flow<AppInstallEvent> = channelFlow {
        withContext(dispatcherProvider.IO) {
            val modes = resolveModes(mode)
            log(TAG, INFO) { "install(${plan.source}, $mode): candidates=$modes" }
            if (modes.isEmpty()) {
                send(AppInstallEvent.Failure(AppInstallNoElevationException(mode)))
                return@withContext
            }

            var lastTransportError: Throwable? = null
            modes.forEachIndexed { index, candidate ->
                try {
                    val obbPlaced = perform(plan, candidate) { send(it) }
                    send(AppInstallEvent.Success(plan.pkgId, candidate, obbPlaced))
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isTransportFailure(e) && index < modes.lastIndex) {
                        log(TAG, WARN) { "install(): $candidate transport failed, trying next: ${e.asLog()}" }
                        lastTransportError = e
                        return@forEachIndexed
                    }
                    log(TAG, ERROR) { "install(): $candidate failed: ${e.asLog()}" }
                    send(AppInstallEvent.Failure(e))
                    return@withContext
                }
            }
            send(AppInstallEvent.Failure(lastTransportError ?: AppInstallNoElevationException(mode)))
        }
    }

    private suspend fun resolveModes(mode: Mode): List<Mode> = when (mode) {
        Mode.AUTO -> buildList {
            if (rootManager.canUseRootNow()) add(Mode.ROOT)
            if (adbManager.canUseAdbNow()) add(Mode.ADB)
            add(Mode.SYSTEM)
        }

        Mode.ROOT -> if (rootManager.canUseRootNow()) listOf(Mode.ROOT) else emptyList()
        Mode.ADB -> if (adbManager.canUseAdbNow()) listOf(Mode.ADB) else emptyList()
        Mode.SYSTEM -> listOf(Mode.SYSTEM)
    }

    private fun isTransportFailure(error: Throwable): Boolean = when (error) {
        is AppInstallTransportException -> true
        is AppInstallException -> false
        is ElevatedAccessUnavailableException,
        is RootUnavailableException,
        is AdbUnavailableException,
        is ServiceConnectionLostException,
        is ShellOpsException,
        -> true

        else -> false
    }

    /** Returns whether expansion files were placed. */
    private suspend fun perform(
        plan: AppInstallPlan,
        mode: Mode,
        send: suspend (AppInstallEvent) -> Unit,
    ): Boolean {
        val staging = openStaging(mode)
        try {
            val staged = staging.stage(plan, send)
            when (mode) {
                Mode.SYSTEM -> installViaSystem(plan, staged, send)
                else -> installViaShell(plan, staged, mode, send)
            }
            return staging.placeObb(plan, send)
        } finally {
            withContext(NonCancellable) { staging.discardQuietly() }
        }
    }

    // region staging

    /**
     * Where extracted APKs live until `pm` or `PackageInstaller` has read them.
     *
     * ADB staging goes to `/data/local/tmp` rather than an app directory because that directory is
     * owned by the shell UID and readable by it on every supported version - the same shell that
     * runs `pm install-write` right after. It is written through [GatewaySwitch] so the local
     * routing policy escalates the write to that very shell.
     */
    private inner class Staging(
        val mode: Mode,
        /** Names the directory this staging owns, and what a concurrent run's sweep skips. */
        val name: String,
        val localDir: File?,
        val shellDir: LocalPath?,
    ) {

        /** Expansion payloads written next to their destination, waiting to be moved onto it. */
        private val obbPartials = mutableListOf<ObbPartial>()

        /** Why no expansion will be placed. Set once; expansions are all-or-nothing. */
        private var obbFailure: String? = null

        suspend fun stage(plan: AppInstallPlan, send: suspend (AppInstallEvent) -> Unit): List<StagedSplit> = when {
            plan.format.isBundle -> stageBundle(plan, send)
            else -> stageApk(plan, send)
        }

        private suspend fun stageApk(
            plan: AppInstallPlan,
            send: suspend (AppInstallEvent) -> Unit,
        ): List<StagedSplit> {
            val split = plan.splits.single()
            send(
                AppInstallEvent.Progress(
                    stage = AppInstallEvent.Stage.EXTRACTING,
                    current = 0L,
                    total = plan.totalBytes,
                    label = split.stagedName,
                )
            )
            // A provider is allowed to report no size at all, and folding that to zero would reject
            // the APK on its first read. Nothing is lost: the declared size came from the same
            // mutable file, and what actually gets installed is settled by [verifyStagedBase].
            val staged = gatewaySwitch.openInputStream(plan.source)
                .use { writeSplit(plan, split, it, enforceSize = split.size > 0L) }
            verifyStagedBase(plan, staged)
            return listOf(staged)
        }

        /**
         * Extracts everything the container contributes - splits and expansion payloads alike - in a
         * single pass over one opened archive handle, after re-reading the index and holding it
         * against the plan. Both matter: the plan was resolved from an earlier read of a file that
         * anyone with access to shared storage may replace in between, and reads spread over
         * several opens could each land on a different container.
         *
         * Content substitution that preserves every value compared here is the accepted residual. A
         * snapshot of the whole container would close it, at a copy of several GB for a game bundle.
         */
        private suspend fun stageBundle(
            plan: AppInstallPlan,
            send: suspend (AppInstallEvent) -> Unit,
        ): List<StagedSplit> {
            val index = reindexAgainstPlan(plan)
            val obbTarget = if (plan.obbEntries.isEmpty()) null else openObbTarget(plan)
            val expansions = if (obbTarget == null) emptyList() else plan.obbEntries

            val pendingSplits = plan.splits.groupBy { it.entryPath }.mapValues { ArrayDeque(it.value) }
            val pendingObb = expansions.groupBy { it.entryPath }.mapValues { ArrayDeque(it.value) }
            val wanted = (plan.splits.map { it.entryPath } + expansions.map { it.entryPath })
                .map { index.entriesBySegments.getValue(entrySegments(it)) }

            val staged = mutableMapOf<AppInstallPlan.Split, StagedSplit>()
            var done = 0L
            archiveService.useEntryStreams(plan.source, wanted) { meta, stream ->
                val entryPath = meta.segments.joinToString("/")
                val split = pendingSplits[entryPath]?.removeFirstOrNull()
                val expansion = if (split == null) pendingObb[entryPath]?.removeFirstOrNull() else null
                if (split != null) {
                    send(
                        AppInstallEvent.Progress(
                            stage = AppInstallEvent.Stage.EXTRACTING,
                            current = done,
                            total = plan.totalBytes,
                            label = split.stagedName,
                        )
                    )
                    staged[split] = writeSplit(plan, split, stream, enforceSize = true)
                    done += split.size
                } else if (expansion != null && obbTarget != null) {
                    send(
                        AppInstallEvent.Progress(
                            stage = AppInstallEvent.Stage.EXTRACTING,
                            current = done,
                            total = plan.totalBytes,
                            label = expansion.fileName,
                        )
                    )
                    stageObb(plan, expansion, obbTarget, stream)
                }
            }
            return plan.splits.map { staged.getValue(it) }
        }

        private suspend fun writeSplit(
            plan: AppInstallPlan,
            split: AppInstallPlan.Split,
            input: InputStream,
            enforceSize: Boolean,
        ): StagedSplit = when {
            localDir != null -> {
                val target = File(localDir, split.stagedName)
                val written = target.outputStream()
                    .use { out -> copyChecked(input, out, split.size.takeIf { enforceSize }, plan.source) }
                StagedSplit(split, LocalPath.build(target), written) { target.inputStream() }
            }

            else -> {
                val target = shellDir!!.child(split.stagedName)
                gatewaySwitch.createFile(target, createParents = false)
                val written = gatewaySwitch.openOutputStream(target, append = false)
                    .use { out -> copyChecked(input, out, split.size.takeIf { enforceSize }, plan.source) }
                StagedSplit(split, target, written) { gatewaySwitch.openInputStream(target) }
            }
        }

        /**
         * Requires the staged base APK to be the package inspection described. Package name, version
         * code and signing certificate together are that identity - size, name and version are all
         * the container's to choose, so none of them binds anything on its own.
         *
         * Only the staging in our own cache can be re-read: the shell staging directory belongs to
         * the shell UID and is not readable from this process.
         */
        private suspend fun verifyStagedBase(plan: AppInstallPlan, staged: StagedSplit) {
            val expected = plan.baseInfo ?: return
            if (localDir == null) return

            val actual = apkArchiveParser.parseFile(staged.path, includeIcon = false)
                ?: throw AppInstallUnsupportedBundleException(plan.source, "the staged file is not a readable APK")
            val matches = actual.id == expected.id &&
                actual.versionCode == expected.versionCode &&
                actual.signatures.map { it.sha256 }.toSet() == expected.signatures.map { it.sha256 }.toSet()
            if (!matches) {
                throw AppInstallUnsupportedBundleException(plan.source, "the staged APK is not the inspected one")
            }
        }

        /**
         * Re-reads the container index and holds it against the plan. The rebuild is forced: an
         * index is served from cache while size and mtime are unchanged, which is exactly what a
         * same-size replacement leaves intact, and a check against it would prove nothing.
         */
        private suspend fun reindexAgainstPlan(plan: AppInstallPlan): ArchiveIndex {
            archiveService.invalidate(plan.source)
            val index = archiveService.index(plan.source)

            fun reject(reason: String): Nothing = throw AppInstallUnsupportedBundleException(plan.source, reason)

            if (index.entriesBySegments.size != plan.indexEntryCount) reject("the container changed since inspection")
            val declared = plan.splits.map { it.entryPath to it.size } +
                plan.obbEntries.map { it.entryPath to it.size }
            declared.forEach { (entryPath, size) ->
                val meta = index.entriesBySegments[entrySegments(entryPath)] ?: reject("$entryPath is gone")
                if (meta.size != size) reject("$entryPath changed size since inspection")
            }
            return index
        }

        /**
         * The directory the expansions of this install belong in, or null with [obbFailure] set.
         *
         * The destination is built from the package the base APK declares, never from what the
         * container claims. On Android 11+ no unprivileged mechanism - MANAGE_EXTERNAL_STORAGE
         * included - may write another package's obb directory, so this only succeeds when the write
         * can escalate to root or ADB; otherwise the run ends success-with-warning.
         */
        private suspend fun openObbTarget(plan: AppInstallPlan): ObbTarget? {
            val packageName = plan.pkgId?.name
            val obbRoot = storageEnvironment.publicObbDirs.firstOrNull()
            if (packageName == null || obbRoot == null) {
                obbFailure = "No obb destination could be resolved"
                return null
            }
            return try {
                val dir = obbRoot.child(packageName)
                gatewaySwitch.createDir(dir, createParents = true)
                // Against a symlinked package directory pointing out of the obb root: what we write
                // into has to be a direct child of the root itself.
                val canonicalRoot = gatewaySwitch.canonicalize(obbRoot)
                val canonicalDir = gatewaySwitch.canonicalize(dir)
                if (!canonicalDir.segments.isChildOf(canonicalRoot.segments)) {
                    throw IOException("$canonicalDir is not inside $canonicalRoot")
                }
                ObbTarget(dir = dir, canonicalPath = canonicalDir.path).also { sweepObbArtifacts(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "No usable expansion destination: ${e.asLog()}" }
                obbFailure = e.message ?: e.toString()
                null
            }
        }

        /**
         * Writes one expansion payload into a partial next to its destination, while the container
         * is still open and proven. Moving it onto the destination waits for the install to commit;
         * re-reading the container after the system dialog closed would mean trusting a file that
         * was reachable for as long as the user took to answer.
         */
        private suspend fun stageObb(
            plan: AppInstallPlan,
            entry: AppInstallPlan.ObbEntry,
            target: ObbTarget,
            input: InputStream,
        ) {
            // One name per operation: a fixed suffix makes two installs of the same bundle write
            // the same partial, and each would then move the other's half-written file into place.
            val operationId = Uuid.random().toString()
            val partial = ObbPartial(
                fileName = entry.fileName,
                path = target.dir.child("${entry.fileName}.$operationId$PARTIAL_SUFFIX"),
                target = target.dir.child(entry.fileName),
                backup = target.dir.child("${entry.fileName}.$operationId$BACKUP_SUFFIX"),
                lockKey = obbLockKey(target, entry.fileName),
            )
            // Held before the file can exist, and let go of only once it is gone again: a partial
            // nothing tracks is several GB under Android/obb that no later run would look at.
            adopt(partial)
            try {
                gatewaySwitch.createFile(partial.path, createParents = false)
                gatewaySwitch.file(partial.path, readWrite = true).use { handle ->
                    handle.sink().buffer().use { sink ->
                        copyChecked(input, sink.outputStream(), entry.size, plan.source)
                        sink.flush()
                    }
                    // Durability through the gateway handle: a FileOutputStream cast reaches the
                    // descriptor of a direct write only, never of one that went through root or ADB.
                    handle.flush()
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { if (deleteQuietly(partial.path)) forget(partial) }
                if (e is CancellationException) throw e
                log(TAG, WARN) { "Failed to stage expansion ${entry.fileName}: ${e.asLog()}" }
                obbFailure = e.message ?: e.toString()
            }
        }

        /** Takes responsibility for [partial], for this staging and process-wide alike. */
        private fun adopt(partial: ObbPartial) {
            obbPartials += partial
            activeObbPartials += partial.path.path
        }

        /** Drops [partial] again. Only called once its file is confirmed gone or moved into place. */
        private fun forget(partial: ObbPartial) {
            obbPartials.remove(partial)
            activeObbPartials.remove(partial.path.path)
        }

        /** Moves the staged expansions onto their destinations. Returns whether they all landed. */
        suspend fun placeObb(plan: AppInstallPlan, send: suspend (AppInstallEvent) -> Unit): Boolean {
            if (plan.obbEntries.isEmpty()) return false
            val incomplete: String? = when {
                obbFailure != null -> obbFailure
                obbPartials.size != plan.obbEntries.size ->
                    "Only ${obbPartials.size} of ${plan.obbEntries.size} expansions were extracted"

                else -> null
            }
            if (incomplete != null) {
                send(AppInstallEvent.ObbFailed(incomplete))
                return false
            }

            val pending = obbPartials.toList()
            return try {
                pending.forEachIndexed { index, partial ->
                    send(
                        AppInstallEvent.Progress(
                            stage = AppInstallEvent.Stage.PLACING_OBB,
                            current = index.toLong(),
                            total = pending.size.toLong(),
                            label = partial.fileName,
                        )
                    )
                    commitObbPartial(partial)
                    forget(partial)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "Expansion placement failed: ${e.asLog()}" }
                send(AppInstallEvent.ObbFailed(e.message ?: e.toString()))
                false
            }
        }

        /** Creates what this staging owns. Its owner already exists, so a failure here is cleanable. */
        suspend fun create() {
            localDir?.let {
                if (!it.mkdirs() && !it.isDirectory) throw AppInstallSessionException("Cannot create staging $it")
            }
            shellDir?.let { gatewaySwitch.createDir(it, createParents = true) }
        }

        suspend fun discard() {
            obbPartials.toList().forEach { if (deleteQuietly(it.path)) forget(it) }
            try {
                localDir?.let {
                    if (!it.deleteRecursively() && it.exists()) {
                        throw AppInstallSessionException("Cannot remove staging $it")
                    }
                }
                shellDir?.let { dir ->
                    val shellMode = if (mode == Mode.ROOT) ShellOps.Mode.ROOT else ShellOps.Mode.ADB
                    val result = shellOps.execute(ShellOpsCmd("rm -rf ${quote(dir.path)}"), shellMode)
                    if (!result.isSuccess) {
                        throw AppInstallSessionException("Cannot remove staging $dir: ${result.failureText()}")
                    }
                }
            } catch (e: Throwable) {
                // Scratch this run could not remove has to stay sweepable: the flag is what stops
                // every later run in this process from looking for it at all.
                if (localDir != null) localSwept.set(false)
                if (shellDir != null) shellSwept.set(false)
                throw e
            } finally {
                // Even when removal failed: what is left is nobody's any more, and a name that stays
                // registered is one no sweep would ever pick up.
                activeStagingNames.remove(name)
            }
        }

        /** Cleanup runs where an install verdict already exists and must not be replaced by it. */
        suspend fun discardQuietly() = try {
            discard()
        } catch (e: Exception) {
            log(TAG, WARN) { "Staging cleanup failed: ${e.asLog()}" }
        }
    }

    private class StagedSplit(
        val split: AppInstallPlan.Split,
        /** Where the staged bytes live; also what the elevated shell is pointed at. */
        val path: LocalPath,
        /** What was actually written, which is what the session has to be sized from. */
        val size: Long,
        val open: suspend () -> InputStream,
    )

    /** The obb directory of the package being installed, plus its resolved real path. */
    private class ObbTarget(
        val dir: APath<*>,
        val canonicalPath: String,
    )

    /** One expansion payload written next to its destination, ready to be moved onto it. */
    private class ObbPartial(
        val fileName: String,
        val path: APath<*>,
        val target: APath<*>,
        val backup: APath<*>,
        val lockKey: String,
    )

    /**
     * The staging object exists before the directory does, so whatever it created is owned by
     * something that can remove it again - a directory created behind a failing return would be
     * scratch nobody is responsible for.
     */
    private suspend fun openStaging(mode: Mode): Staging {
        // Registered before the sweep can list it: a sweep removes the whole staging root minus what
        // is in use, and an install starting while another is extracting must not take its directory.
        val name = Uuid.random().toString()
        activeStagingNames += name
        val staging = try {
            if (mode == Mode.ADB) {
                sweepShellStaging()
                Staging(mode, name, localDir = null, shellDir = LocalPath.build(SHELL_STAGING_ROOT, name))
            } else {
                sweepLocalStaging()
                val root = context.cacheDir?.let { File(it, LOCAL_STAGING_DIRNAME) }
                    ?: throw AppInstallSessionException("No cache directory available for staging")
                Staging(mode, name, localDir = File(root, name), shellDir = null)
            }
        } catch (e: Throwable) {
            activeStagingNames.remove(name)
            throw e
        }
        try {
            staging.create()
        } catch (e: Throwable) {
            withContext(NonCancellable) { staging.discardQuietly() }
            throw e
        }
        return staging
    }

    /**
     * Sweeps scratch left behind by a crashed run. Once per process, before anything is created, and
     * child by child rather than the root as a whole: the root also holds the staging of every
     * install that is extracting right now.
     */
    private suspend fun sweepLocalStaging() {
        if (localSwept.get()) return
        sweepMutex.withLock {
            if (localSwept.get()) return
            val root = context.cacheDir?.let { File(it, LOCAL_STAGING_DIRNAME) }
            val swept = try {
                root?.listFiles()
                    ?.filter { it.name !in activeStagingNames }
                    ?.map { it.deleteRecursively() }
                    ?.all { it }
                    ?: true
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to sweep local staging: ${e.asLog()}" }
                false
            }
            // A sweep that did not happen must stay pending: the flag is what stops the next run
            // from looking, and scratch left behind is never noticed again in this process.
            if (swept) localSwept.set(true)
        }
    }

    private suspend fun sweepShellStaging() {
        if (shellSwept.get()) return
        sweepMutex.withLock {
            if (shellSwept.get()) return
            val root = LocalPath.build(SHELL_STAGING_ROOT)
            val swept = try {
                when {
                    !gatewaySwitch.exists(root) -> true
                    else -> gatewaySwitch.listFiles(root)
                        .filter { it.name !in activeStagingNames }
                        .map { child ->
                            val removal = shellOps.execute(
                                ShellOpsCmd("rm -rf ${quote(child.path)}"),
                                ShellOps.Mode.ADB,
                            )
                            if (!removal.isSuccess) {
                                log(TAG, WARN) { "Failed to sweep $child: ${removal.failureText()}" }
                            }
                            removal.isSuccess
                        }
                        .all { it }
                }
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to sweep shell staging: ${e.asLog()}" }
                false
            }
            if (swept) shellSwept.set(true)
        }
    }

    private fun entrySegments(entryPath: String): List<String> = entryPath.split('/').filter { it.isNotEmpty() }

    /**
     * Copies exactly [declaredSize] bytes, or all of them when nothing was declared. A length other
     * than the declared one is a lie about the container rather than an unusual file: more would
     * fill storage, less is a different entry than the one that was inspected, and both abort
     * instead of installing what arrived.
     */
    private fun copyChecked(input: InputStream, out: OutputStream, declaredSize: Long?, source: APath<*>): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            written += read
            if (declaredSize != null && written > declaredSize) {
                throw AppInstallUnsupportedBundleException(source, "entry exceeds its declared size")
            }
            out.write(buffer, 0, read)
        }
        out.flush()
        if (declaredSize != null && written != declaredSize) {
            throw AppInstallUnsupportedBundleException(source, "entry is shorter than its declared size")
        }
        return written
    }

    // endregion

    // region expansion placement

    private val obbLocks = mutableMapOf<String, Mutex>()
    private val obbLocksGuard = Mutex()

    /** Case-folded so two names that address the same file on case-insensitive storage share a lock. */
    private fun obbLockKey(target: ObbTarget, fileName: String): String =
        "${target.canonicalPath}/$fileName".lowercase()

    private suspend fun obbLock(key: String): Mutex = obbLocksGuard.withLock { obbLocks.getOrPut(key) { Mutex() } }

    /**
     * Puts one staged expansion in place without ever leaving its destination missing: an existing
     * file is moved aside first and moved back if the swap fails. For a sideloaded game the bundled
     * expansion is usually the only obtainable copy, and a delete-then-move would destroy it for
     * good if the process died in between - silently, since the install itself already succeeded.
     */
    private suspend fun commitObbPartial(partial: ObbPartial) = obbLock(partial.lockKey).withLock {
        settleBackup(partial.target, partial.backup)
        try {
            if (gatewaySwitch.exists(partial.target)) requireMoved(partial.target, partial.backup)
            requireMoved(partial.path, partial.target)
        } finally {
            // Every exit, cancellation included, and both moves inside the guard: between them the
            // only copy of the expansion is the backup, so a settle that is skipped here loses it.
            withContext(NonCancellable) {
                try {
                    settleBackup(partial.target, partial.backup)
                } catch (settle: Exception) {
                    log(TAG, ERROR) { "Settle failed, original kept at ${partial.backup}: ${settle.asLog()}" }
                }
            }
        }
    }

    /** Restores a backup whose target is gone, drops one whose target is there. */
    private suspend fun settleBackup(target: APath<*>, backup: APath<*>) {
        if (!gatewaySwitch.exists(backup)) return
        if (gatewaySwitch.exists(target)) deleteQuietly(backup) else requireMoved(backup, target)
    }

    /**
     * Finishes the transactions a killed run left half-done in the destination directory: a backup
     * whose swap never completed goes back onto its destination, and a partial nobody is filling any
     * more is several GB of scratch that only this sweep will ever remove.
     */
    private suspend fun sweepObbArtifacts(target: ObbTarget) {
        gatewaySwitch.listFiles(target.dir).forEach { artifact ->
            when {
                artifact.name.endsWith(BACKUP_SUFFIX) -> {
                    val original = transactionOrigin(artifact.name, BACKUP_SUFFIX) ?: return@forEach
                    obbLock(obbLockKey(target, original)).withLock {
                        settleBackup(target.dir.child(original), artifact)
                    }
                }

                artifact.name.endsWith(PARTIAL_SUFFIX) -> {
                    if (transactionOrigin(artifact.name, PARTIAL_SUFFIX) == null) return@forEach
                    // A partial another install is filling right now is not leftovers.
                    if (activeObbPartials.contains(artifact.path)) return@forEach
                    deleteQuietly(artifact)
                }
            }
        }
    }

    /**
     * The destination name behind a `<name>.<operation id>.<suffix>` artifact, or null when the name
     * is not one Butler wrote - `download.part` is somebody else's file, not scratch.
     */
    private fun transactionOrigin(artifactName: String, suffix: String): String? {
        val withoutSuffix = artifactName.removeSuffix(suffix)
        val operationId = withoutSuffix.substringAfterLast('.', "")
        if (runCatching { Uuid.parse(operationId) }.isFailure) return null
        return withoutSuffix.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
    }

    /**
     * A move that reports [MoveOutcome.NotSupported] moved nothing, so a discarded outcome is how a
     * placement that never happened is reported as done.
     */
    private suspend fun requireMoved(source: APath<*>, destination: APath<*>) {
        val outcome = gatewaySwitch.move(source, destination)
        if (outcome !is MoveOutcome.Moved) throw IOException("Cannot move $source onto $destination: $outcome")
    }

    /** Returns whether [path] is gone afterwards; failing to remove it is never fatal by itself. */
    private suspend fun deleteQuietly(path: APath<*>): Boolean = try {
        if (gatewaySwitch.exists(path)) gatewaySwitch.delete(path, recursive = false)
        true
    } catch (e: Exception) {
        log(TAG, WARN) { "Failed to remove $path: ${e.asLog()}" }
        false
    }

    // endregion

    // region elevated install

    private suspend fun installViaShell(
        plan: AppInstallPlan,
        staged: List<StagedSplit>,
        mode: Mode,
        send: suspend (AppInstallEvent) -> Unit,
    ) {
        val shellMode = if (mode == Mode.ROOT) ShellOps.Mode.ROOT else ShellOps.Mode.ADB
        val userId = userManager2.currentUser().handle.handleId

        // Opened before the session can exist: a create that half-succeeded, or one whose answer we
        // cannot read, still leaves a session holding the space it was told to reserve.
        var sessionId: Int? = null
        var committed = false
        try {
            // Sized from what staging actually wrote, not from what the container declared: for a
            // plain APK behind a provider that reports no size there is no declared total at all.
            val created = shellOps.execute(
                ShellOpsCmd("pm install-create -r -t -S ${staged.sumOf { it.size }} --user $userId"),
                shellMode,
            )
            // Both streams: OEM `pm` replacements and Shizuku-style wrappers put the created line on
            // stderr, and losing the id there would leak the session it announces.
            sessionId = SESSION_ID_PATTERN.find((created.output + created.errors).joinToString("\n"))
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
            when {
                !created.isSuccess -> throw AppInstallSessionException(created.failureText())
                // `pm` reported success in a shape we cannot read: that is the shell answering
                // oddly, not the package being rejected, so the next mode is still worth trying.
                sessionId == null -> throw AppInstallTransportException(created.failureText())
            }

            staged.forEachIndexed { index, entry ->
                send(
                    AppInstallEvent.Progress(
                        stage = AppInstallEvent.Stage.WRITING,
                        current = index.toLong(),
                        total = staged.size.toLong(),
                        label = entry.split.stagedName,
                    )
                )
                val written = shellOps.execute(
                    ShellOpsCmd(
                        "pm install-write -S ${entry.size} $sessionId " +
                            "${quote(entry.split.stagedName)} ${quote(entry.path.path)}"
                    ),
                    shellMode,
                )
                if (!written.isSuccess) throw AppInstallSessionException(written.failureText())
            }

            send(
                AppInstallEvent.Progress(
                    stage = AppInstallEvent.Stage.COMMITTING,
                    current = staged.size.toLong(),
                    total = staged.size.toLong(),
                )
            )
            val commit = shellOps.execute(ShellOpsCmd("pm install-commit $sessionId"), shellMode)
            // Both streams, for the same reason the create parse reads both: a `pm` that answers on
            // stderr would otherwise have an install reported as failed right after it succeeded.
            if (!commit.isSuccess || (commit.output + commit.errors).none { it.contains("Success") }) {
                throw AppInstallSessionException(commit.failureText())
            }
            committed = true
        } finally {
            val abandonable = sessionId?.takeIf { !committed }
            if (abandonable != null) {
                withContext(NonCancellable) {
                    try {
                        shellOps.execute(ShellOpsCmd("pm install-abandon $abandonable"), shellMode)
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Failed to abandon session $abandonable: ${e.asLog()}" }
                    }
                }
            }
        }
    }

    private fun ShellOpsResult.failureText(): String = (errors + output)
        .firstOrNull { it.isNotBlank() }
        ?: "exit code $exitCode"

    // endregion

    // region system install

    private suspend fun installViaSystem(
        plan: AppInstallPlan,
        staged: List<StagedSplit>,
        send: suspend (AppInstallEvent) -> Unit,
    ) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        plan.pkgId?.let { params.setAppPackageName(it.name) }

        val sessionId = installer.createSession(params)
        val requestId = Uuid.random().toString()
        var succeeded = false
        try {
            installer.openSession(sessionId).use { session ->
                staged.forEachIndexed { index, entry ->
                    send(
                        AppInstallEvent.Progress(
                            stage = AppInstallEvent.Stage.WRITING,
                            current = index.toLong(),
                            total = staged.size.toLong(),
                            label = entry.split.stagedName,
                        )
                    )
                    val out = session.openWrite(entry.split.stagedName, 0, entry.size)
                    try {
                        entry.open().use { input -> copyChecked(input, out, entry.size, plan.source) }
                        session.fsync(out)
                    } finally {
                        out.close()
                    }
                }

                send(
                    AppInstallEvent.Progress(
                        stage = AppInstallEvent.Stage.COMMITTING,
                        current = staged.size.toLong(),
                        total = staged.size.toLong(),
                    )
                )
                commitAndAwait(installer, session, sessionId, requestId)
            }
            succeeded = true
        } finally {
            if (!succeeded) {
                withContext(NonCancellable) {
                    try {
                        installer.abandonSession(sessionId)
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Failed to abandon session $sessionId: ${e.asLog()}" }
                    }
                }
            }
        }
    }

    private suspend fun commitAndAwait(
        installer: PackageInstaller,
        session: PackageInstaller.Session,
        sessionId: Int,
        requestId: String,
    ) = coroutineScope {
        val statuses = Channel<AppInstallStatusRelay.Status>(Channel.BUFFERED)
        // UNDISPATCHED so the collector is registered before commit() can produce a callback.
        val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
            statusRelay.statuses.filter { it.requestId == requestId }.collect { statuses.send(it) }
        }
        try {
            session.commit(buildStatusIntent(sessionId, requestId).intentSender)

            while (true) {
                val status = awaitStatus(installer, sessionId, statuses)
                    ?: throw AppInstallSessionException("The system installer never reported a result")
                when (status.status) {
                    PackageInstaller.STATUS_SUCCESS -> return@coroutineScope
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = status.userAction
                            ?: throw AppInstallSessionException("No install confirmation was offered")
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }

                    else -> throw AppInstallSessionException(
                        status.message ?: "The installer reported status ${status.status}"
                    )
                }
            }
        } finally {
            subscription.cancel()
            statuses.close()
        }
    }

    /**
     * Waits for the session's own verdict. The wait is not a plain deadline: while the session is
     * still open the user may simply be looking at the confirmation dialog, and reporting a failure
     * there would be contradicted by the success that follows.
     */
    private suspend fun awaitStatus(
        installer: PackageInstaller,
        sessionId: Int,
        statuses: Channel<AppInstallStatusRelay.Status>,
    ): AppInstallStatusRelay.Status? {
        val deadline = SystemClock.elapsedRealtime() + SESSION_MAX_WAIT_MS
        var goneSince: Long? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            withTimeoutOrNull(SESSION_POLL_INTERVAL_MS) { statuses.receive() }?.let { return it }

            val alive = try {
                installer.getSessionInfo(sessionId) != null
            } catch (e: Exception) {
                log(TAG, WARN) { "Session $sessionId lookup failed: ${e.asLog()}" }
                false
            }
            when {
                alive -> goneSince = null
                goneSince == null -> goneSince = SystemClock.elapsedRealtime()
                SystemClock.elapsedRealtime() - goneSince > SESSION_GONE_GRACE_MS -> return null
            }
        }
        return null
    }

    private fun buildStatusIntent(sessionId: Int, requestId: String): PendingIntent {
        val intent = Intent(context, AppInstallStatusReceiver::class.java).apply {
            action = AppInstallStatusReceiver.ACTION_INSTALL_STATUS
            setPackage(context.packageName)
            putExtra(AppInstallStatusReceiver.EXTRA_REQUEST_ID, requestId)
        }
        // Mutable is mandatory: PackageInstaller fills in the status extras, and an immutable status
        // receiver is rejected outright at Butler's target SDK.
        val mutable = if (hasApiLevel(31)) {
            @Suppress("NewApi")
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }

    // endregion

    /** Single-quotes an argument so nothing an archive named can be read as shell syntax. */
    private fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    companion object {
        private val TAG = logTag("Pkg", "Installer")
        private const val LOCAL_STAGING_DIRNAME = "install-staging"
        private const val SHELL_STAGING_ROOT = "/data/local/tmp/butler-install"
        private const val PARTIAL_SUFFIX = ".part"
        private const val BACKUP_SUFFIX = ".btlbak"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val SESSION_POLL_INTERVAL_MS = 2_000L
        private const val SESSION_GONE_GRACE_MS = 4_000L
        private const val SESSION_MAX_WAIT_MS = 10 * 60 * 1000L
        private val SESSION_ID_PATTERN = Regex("""\[(\d+)]""")
    }
}

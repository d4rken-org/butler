package eu.darken.butler.viewer.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import eu.darken.butler.common.pkgs.current
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.user.UserManager2
import eu.darken.butler.viewer.core.operations.DeleteOperation
import eu.darken.butler.viewer.core.operations.ViewerCommand
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceDisplay
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.isPausableAsChild
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.stateInWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Single-file workspace that renders one image full screen. The workspace owns the classification
 * of the target path; the ViewModel and page only render what it resolved.
 */
class ViewerWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: ViewerArguments,
    dispatcherProvider: DispatcherProvider,
    private val gatewaySwitch: GatewaySwitch,
    private val contentReader: ViewerContentReader,
    private val imageProbe: ImageProbe,
    private val apkArchiveParser: ApkArchiveParser,
    private val pkgRepo: PkgRepo,
    private val userManager2: UserManager2,
    private val pdfPreviewLoader: PdfPreviewLoader,
    private val operationsManager: OperationsManager,
    private val issueHandler: IssueHandler,
    private val deleteOperationFactory: DeleteOperation.Factory,
) : Workspace<ViewerArguments> {

    private val tag = logTag("Viewer", "Workspace", id.shortTag)
    private val scope = CoroutineScope(
        dispatcherProvider.IO +
            CoroutineName(tag) +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
            }
    )

    override val type: Workspace.Type = Workspace.Type.VIEWER

    /** What this viewer shows: a file on the device, or content streamed from another app. */
    val source: ViewerSource = creationArguments.toViewerSource()

    /**
     * The backing file, or null for streamed content. Only for the operations that genuinely cannot
     * work without one - delete, APK parsing, "open location" - each of which has to say what it
     * does when there is none.
     */
    val storedPath: APath<*>? = (source as? ViewerSource.Stored)?.path

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow

    private var loadJob: Job? = null

    /** One probe at a time, so two poll ticks cannot interleave their verdicts. */
    private val externalChangeMutex = Mutex()

    private val seedDisplay = deriveViewerDisplay(creationArguments)

    // Only the concrete variant carries a caller; the sealed interface itself is not caller-aware.
    private val callerArguments = creationArguments as? Workspace.ArgumentsWithCaller

    override val info: StateFlow<Workspace.Info> = stateFlow.map { current ->
        Workspace.Info(
            id = id,
            type = type,
            title = seedDisplay.title ?: type.label,
            subtitle = seedDisplay.subtitle,
            // A failed or unsupported file stays Ready on purpose: the page renders its own
            // explanation plus retry and "Open with", which the global error overlay would hide.
            lifecycleState = when (current.content) {
                is ViewerContent.Loading -> Workspace.LifecycleState.Initializing
                else -> Workspace.LifecycleState.Ready
            },
            callerWorkspaceId = callerArguments?.callerWorkspaceId,
            modalPresentation = callerArguments?.modalPresentation
                ?: Workspace.ModalPresentationMode.PANE_LOCAL,
            // Built by hand instead of via initialInfo(), so the relationship fields have to be
            // carried explicitly - a missing one here silently reads as "not pausable with my owner"
            pausableAsChild = creationArguments.isPausableAsChild,
            contentPath = storedPath,
            // Both of these MUST be repeated here, not just in initialInfo() below: this hand-built
            // block replaces the seed on the first emission, so a missing flag would silently revert
            // to the permissive default and only show up as a dead tab after a restart.
            isPersistable = creationArguments.isPersistable,
            // Streamed content is never auto-paused. Pausing holds the arguments and rebuilds later,
            // and the URI grant can lapse in between - so a tab the user never touched would come
            // back unreadable. It costs nothing to keep: the viewer's state holds no bitmap, both
            // the decoded image and the rendered page live in the ViewModel.
            isPausable = source is ViewerSource.Stored,
        )
    }.stateInWorkspace(
        scope = scope,
        initial = initialInfo(
            title = seedDisplay.title ?: type.label,
            subtitle = seedDisplay.subtitle,
            arguments = creationArguments,
        ),
    )

    init {
        log(tag, INFO) { "Initialized for $source" }
        reload()
    }

    fun reload() {
        log(tag) { "reload()" }
        loadJob?.cancel()
        loadJob = scope.launch { load() }
    }

    /**
     * Submits the delete and returns its operation id. The operation may already have finished by
     * the time this returns, so a caller waiting on the outcome has to be listening beforehand
     * rather than starting from the returned id.
     */
    suspend fun delete(forcePermDelete: Boolean): Operation.Id {
        log(tag, INFO) { "delete(forcePermDelete=$forcePermDelete) for $source" }
        // Streamed content is not ours to delete and the UI hides the action for it, so reaching
        // here without a path is a bug rather than a state to handle.
        val filePath = requireNotNull(storedPath) { "Cannot delete streamed content" }
        val executable = deleteOperationFactory.create(
            workspaceId = id,
            command = ViewerCommand.Delete(
                targets = setOf(filePath),
                options = ViewerCommand.Delete.Options(forcePermDelete = forcePermDelete),
            ),
        )
        return operationsManager.submit(executable)
    }

    /**
     * Compares the file on display against the metadata it was loaded with, and latches the verdict
     * into [State.externalChange].
     *
     * The flag only ever escalates: nothing but a [reload] clears it, and [ViewerExternalChange.Gone]
     * is terminal. A probe that cannot reach the file changes nothing, because an unreadable file is
     * no evidence either way.
     *
     * Size plus mtime is all a gateway can offer cheaply. SAF and the root/ADB gateways report a
     * coarse or absent mtime, so an in-place edit that keeps the size can go undetected there - the
     * same limitation the editor's poll-side probe has.
     */
    suspend fun checkExternalChange() = externalChangeMutex.withLock {
        try {
            probeExternalChange()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "checkExternalChange() failed - ${e.asLog()}" }
        }
    }

    private suspend fun probeExternalChange() {
        if (source !is ViewerSource.Stored) return
        val snapshot = stateFlow.value
        if (snapshot.externalChange == ViewerExternalChange.Gone) return
        // Nothing was ever rendered, so there is no baseline a verdict could be about.
        val baseline = snapshot.contentLookup ?: return
        val effectivePath = baseline.lookedUp

        val current = try {
            gatewaySwitch.useRes { gatewaySwitch.lookup(effectivePath, LookupOptions.BASE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag) { "Probe lookup failed for $effectivePath: ${e.asLog()}" }
            if (isGone(effectivePath)) latchExternalChange(baseline, ViewerExternalChange.Gone)
            return
        }

        val changed = current.size != baseline.size ||
            (baseline.modifiedAt != null && current.modifiedAt != null && current.modifiedAt != baseline.modifiedAt)
        if (changed) latchExternalChange(baseline, ViewerExternalChange.Modified)
    }

    private fun latchExternalChange(baseline: APathLookup<*>, verdict: ViewerExternalChange) {
        stateFlow.update { current ->
            when {
                // A probe suspended across a load must not land a verdict about the file the
                // previous baseline described.
                current.contentLookup !== baseline -> current
                current.externalChange == ViewerExternalChange.Gone -> current
                current.externalChange == ViewerExternalChange.Modified &&
                    verdict != ViewerExternalChange.Gone -> current

                else -> {
                    log(tag, INFO) { "External change for ${baseline.lookedUp}: $verdict" }
                    current.copy(externalChange = verdict)
                }
            }
        }
    }

    fun resolveConflict(operationId: Operation.Id, resolution: PathActionIssue.Resolution) {
        log(tag, INFO) { "resolveConflict($operationId, $resolution)" }
        scope.launch { issueHandler.resolveIssue(operationId, resolution) }
    }

    /**
     * The creation arguments verbatim: a viewer holds no state beyond its file path, and the caller
     * has to survive. Pause captures these and rebuilds the workspace from them, so dropping the
     * caller would resume a drill-down as a tab - outside its ownership unit and no longer closing
     * with the workspace that opened it.
     */
    override suspend fun createArguments(): ViewerArguments = creationArguments

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        scope.cancel()
    }

    private suspend fun load() {
        stateFlow.value = State()
        when (val current = source) {
            is ViewerSource.Stored -> loadStored(current)
            is ViewerSource.Streamed -> loadStreamed(current)
        }
    }

    /**
     * Streamed content has nothing to look up: everything a gateway lookup would answer either does
     * not exist for a ContentProvider grant (permissions, ownership, file type) or was already
     * reported by the sending app.
     */
    private suspend fun loadStreamed(streamed: ViewerSource.Streamed) {
        val fileInfo = ViewerFileInfo(size = streamed.sizeBytes)

        val rejection = validateStreamed(streamed)
        if (rejection != null) {
            log(tag, WARN) { "Rejecting $streamed: $rejection" }
            stateFlow.value = State(content = ViewerContent.Failed(rejection), fileInfo = fileInfo)
            return
        }

        // The type the sender declared, never the display name: provider names routinely have no
        // extension, and name-based classification would send this to the wrong renderer.
        classify(streamed.mime, fileInfo, lookup = null)
    }

    private suspend fun loadStored(stored: ViewerSource.Stored) {
        val filePath = stored.path
        val lookup = try {
            gatewaySwitch.useRes { gatewaySwitch.lookup(filePath, LookupOptions.BASE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Lookup failed for $filePath: ${e.asLog()}" }
            // A file deleted behind the viewer's back surfaces as a gateway permission error, which
            // would send the user looking for an access problem that does not exist.
            val error = if (isGone(filePath)) ViewerFileGoneException(filePath, e) else e
            stateFlow.value = State(content = ViewerContent.Failed(error))
            return
        }

        val fileInfo = ViewerFileInfo(
            size = lookup.size,
            modifiedAt = lookup.modifiedAt,
            createdAt = lookup.createdAt,
            permissions = lookup.permissions,
            ownership = lookup.ownership,
        )

        // A restored session can point at a path that has since become a directory, a dangling
        // symlink or a truncated file. None of those may render as a blank image.
        val validation = validate(filePath, lookup)
        if (validation is Validation.Rejected) {
            log(tag, WARN) { "Rejecting $filePath: ${validation.error}" }
            stateFlow.value = State(
                content = ViewerContent.Failed(validation.error),
                fileInfo = fileInfo,
                lookup = lookup,
                contentLookup = validation.contentLookup,
            )
            return
        }

        classify(
            mime = MimeInfo.fromFileName(lookup.name),
            fileInfo = fileInfo,
            lookup = lookup,
            contentLookup = (validation as Validation.Accepted).contentLookup,
        )
    }

    private suspend fun classify(
        mime: MimeInfo,
        fileInfo: ViewerFileInfo,
        lookup: APathLookup<*>?,
        contentLookup: APathLookup<*>? = null,
    ) {
        // By name and ahead of every MIME branch: senders declare archives as anything from
        // image/png to application/pdf, and reaching the image probe or the PDF seek check with a
        // container would end as "damaged" or "unreadable" instead of something we can offer to
        // browse. Compound suffixes (.tar.gz) are why this is a name question in the first place.
        val archiveFormat = ArchiveFormat.fromFileName(source.displayName)
        if (archiveFormat != null) {
            val access = when {
                source !is ViewerSource.Stored -> ViewerContent.Archive.Access.NEEDS_COPY
                // Nested containers are not browsable: the gateway refuses an archive inside an
                // archive, and there is nothing to save a copy from either.
                source.path is ArchivePath -> ViewerContent.Archive.Access.NESTED
                else -> ViewerContent.Archive.Access.BROWSABLE
            }
            log(tag, INFO) { "$source is a $archiveFormat archive ($access)" }
            stateFlow.value = State(
                content = ViewerContent.Archive(mime = mime, format = archiveFormat, access = access),
                fileInfo = fileInfo,
                lookup = lookup,
                // A container is a file that can be replaced or removed while its placeholder is on
                // display, and the Browse offer would then point at something that is no longer
                // there - so it is watched like every other accepted file.
                contentLookup = contentLookup,
            )
            return
        }

        if (mime.isApk) {
            // The framework parser takes a path, so an APK always arrives as a real file - the
            // arrival flow imports one rather than streaming it. Anything else is a routing bug.
            val apkPath = storedPath
            if (apkPath == null) {
                log(tag, WARN) { "Streamed APK cannot be parsed, no path: $source" }
                stateFlow.value = State(content = ViewerContent.Unsupported(mime), fileInfo = fileInfo)
                return
            }
            loadApk(apkPath, mime, fileInfo, lookup, contentLookup)
            return
        }

        // Page count doubles as the render check: a document that cannot be opened here would render
        // as a permanently blank canvas, so it goes to the unsupported placeholder instead.
        if (mime.isPdf) {
            val streamed = source as? ViewerSource.Streamed
            val rejection = streamed?.let { validateSeekable(it) }
            if (rejection != null) {
                log(tag, WARN) { "Rejecting $source: $rejection" }
                stateFlow.value = State(content = ViewerContent.Failed(rejection), fileInfo = fileInfo)
                return
            }
            val pageCount = pdfPreviewLoader.pageCount(source)
            stateFlow.value = if (pageCount == null) {
                log(tag, WARN) { "$source is a PDF that cannot be rendered" }
                State(
                    content = ViewerContent.Unsupported(mime),
                    fileInfo = fileInfo,
                    lookup = lookup,
                    contentLookup = contentLookup,
                )
            } else {
                log(tag, INFO) { "$source is a PDF with $pageCount page(s)" }
                State(
                    content = ViewerContent.PdfPreview(mime, pageCount),
                    fileInfo = fileInfo,
                    lookup = lookup,
                    contentLookup = contentLookup,
                )
            }
            return
        }

        if (!mime.isImage) {
            log(tag, INFO) { "$source is not an image ($mime)" }
            stateFlow.value = State(
                content = ViewerContent.Unsupported(mime),
                fileInfo = fileInfo,
                lookup = lookup,
                contentLookup = contentLookup,
            )
            return
        }

        // The probe doubles as the decode check. It has to run before the image is announced,
        // otherwise bytes the decoder rejects would render as a permanently blank canvas.
        val imageInfo = when (val probe = imageProbe.probe(source)) {
            is ProbeResult.Probed -> ViewerFileInfo.ImageInfo(
                format = probe.format,
                width = probe.width,
                height = probe.height,
            )

            ProbeResult.NoRasterDimensions -> {
                // Vector formats legitimately have none. For a raster format it means the decoder
                // could not even read the header, i.e. the bytes are corrupt or truncated.
                if (!mime.isVectorImage) {
                    val error = ViewerUndecodableImageException(source.displayName)
                    log(tag, WARN) { "Rejecting $source: $error" }
                    stateFlow.value = State(
                        content = ViewerContent.Failed(error),
                        fileInfo = fileInfo,
                        lookup = lookup,
                        contentLookup = contentLookup,
                    )
                    return
                }
                ViewerFileInfo.ImageInfo(format = mime.rawType)
            }

            is ProbeResult.ProbeFailed -> {
                // A stream that cannot be opened or read is a real failure for any format.
                log(tag, WARN) { "Probing $source failed: ${probe.error.asLog()}" }
                stateFlow.value = State(
                    content = ViewerContent.Failed(probe.error),
                    fileInfo = fileInfo,
                    lookup = lookup,
                    contentLookup = contentLookup,
                )
                return
            }
        }

        stateFlow.value = State(
            content = ViewerContent.Image(mime),
            fileInfo = fileInfo.copy(imageInfo = imageInfo),
            lookup = lookup,
            contentLookup = contentLookup,
        )
    }

    private suspend fun loadApk(
        filePath: APath<*>,
        mime: MimeInfo,
        fileInfo: ViewerFileInfo,
        lookup: APathLookup<*>?,
        contentLookup: APathLookup<*>?,
    ) {
        val apkInfo = apkArchiveParser.parseFile(filePath)
        if (apkInfo == null) {
            val error = ViewerApkParseException(filePath)
            log(tag, WARN) { "Rejecting $filePath: $error" }
            stateFlow.value = State(
                content = ViewerContent.Failed(error),
                fileInfo = fileInfo,
                lookup = lookup,
                contentLookup = contentLookup,
            )
            return
        }

        val installState = try {
            // Read through the package data itself instead of the repo's per-id query: the query
            // path serves the cache map directly, so a repo whose data failed to build answers
            // "empty" and would read as "not installed". Cross-user entries only exist with
            // elevated access; the any-user match is the fallback so a work-profile install still
            // counts as installed.
            val packages = pkgRepo.current()
            val installed = packages
                .firstOrNull { it.id == apkInfo.id && it.userHandle == userManager2.currentUser().handle }
                ?: packages.firstOrNull { it.id == apkInfo.id }
            when (installed) {
                null -> ApkInstallState.NotInstalled
                else -> ApkInstallState.Installed(
                    versionName = installed.versionName,
                    versionCode = installed.versionCode,
                    comparison = compareVersions(apkInfo.versionCode, installed.versionCode),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed lookup must not read as "not installed" - that is a different statement.
            log(tag, WARN) { "Installed lookup failed for ${apkInfo.id}: ${e.asLog()}" }
            ApkInstallState.Unknown
        }

        log(tag, INFO) { "$filePath is an APK: ${apkInfo.id} ($installState)" }
        stateFlow.value = State(
            content = ViewerContent.Apk(mime = mime, apkInfo = apkInfo, installState = installState),
            fileInfo = fileInfo,
            lookup = lookup,
            contentLookup = contentLookup,
        )
    }

    /** Only a definitive "not there" counts; a failing check stays with the original error. */
    private suspend fun isGone(path: APath<*>?): Boolean = try {
        val filePath = path ?: return false
        !gatewaySwitch.useRes { gatewaySwitch.exists(filePath) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(tag, WARN) { "Existence check failed for $path: ${e.asLog()}" }
        false
    }

    /**
     * Outcome of [validate]. Both outcomes can name the file this viewer is bound to - for a symlink
     * the target rather than the link node - which becomes [State.contentLookup] and with it the
     * baseline [checkExternalChange] compares against.
     */
    private sealed interface Validation {
        /**
         * [contentLookup] is set only where watching the file can still lead somewhere, i.e. for an
         * empty file that may yet gain content. A directory, a non-file or a broken symlink stays
         * without one on purpose: a directory's mtime moves with every change inside it, which would
         * put a "File changed" banner over a "Not a file" placeholder for a reason the user cannot
         * act on.
         */
        data class Rejected(val error: Throwable, val contentLookup: APathLookup<*>? = null) : Validation

        data class Accepted(val contentLookup: APathLookup<*>) : Validation
    }

    private suspend fun validate(filePath: APath<*>, lookup: APathLookup<APath<*>>): Validation =
        when (lookup.fileType) {
            FileType.FILE -> when (lookup.size) {
                0L -> Validation.Rejected(ViewerEmptyFileException(filePath), contentLookup = lookup)
                else -> Validation.Accepted(lookup)
            }

            FileType.DIRECTORY, FileType.UNKNOWN -> Validation.Rejected(ViewerNotAFileException(filePath))
            FileType.SYMBOLIC_LINK -> validateSymlink(filePath, lookup)
        }

    /**
     * Single-level resolution, which is also what the external-change probe compares against: the
     * link node's own metadata never moves when its target is rewritten or deleted.
     */
    private suspend fun validateSymlink(filePath: APath<*>, lookup: APathLookup<APath<*>>): Validation {
        val target = lookup.target ?: return Validation.Rejected(ViewerBrokenSymlinkException(filePath))
        val targetLookup = try {
            gatewaySwitch.useRes { gatewaySwitch.lookup(target, LookupOptions.BASE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Symlink target lookup failed for $target: ${e.asLog()}" }
            return Validation.Rejected(ViewerBrokenSymlinkException(filePath))
        }
        return when {
            targetLookup.fileType != FileType.FILE -> Validation.Rejected(ViewerNotAFileException(filePath))
            // The target is the file that would grow, not the link.
            targetLookup.size == 0L -> Validation.Rejected(
                ViewerEmptyFileException(filePath),
                contentLookup = targetLookup,
            )
            else -> Validation.Accepted(targetLookup)
        }
    }

    /**
     * The streamed counterpart of [validate]. Directories and symlinks have no meaning for a
     * ContentProvider grant, but empty content still does, and a grant that has lapsed is a failure
     * a path never has.
     *
     * Checked up front so the PDF and image branches report the same, true reason instead of each
     * blaming the format for something that is really about access. Deliberately says nothing about
     * seekability ([validateSeekable]): that question belongs to the PDF branch, i.e. after
     * classification, or a container the sender mislabelled as a PDF would be rejected here before
     * it is ever recognized as an archive.
     */
    private suspend fun validateStreamed(streamed: ViewerSource.Streamed): Throwable? {
        // One real read rather than the size the provider claims: a stale or placeholder zero from
        // a cloud provider would otherwise reject a file that has content, and a lapsed grant that
        // also reports zero would be called empty when it is really unreadable.
        val hasBytes = try {
            contentReader.readInput(streamed) { it.read() != -1 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            log(tag, WARN) { "Grant for $streamed has lapsed: ${e.asLog()}" }
            return ViewerContentUnreadableException(streamed.displayName, e)
        } catch (e: Exception) {
            log(tag, WARN) { "Cannot read $streamed: ${e.asLog()}" }
            return ViewerContentUnreadableException(streamed.displayName, e)
        }
        if (!hasBytes) return ViewerEmptyFileException(streamed.displayName)

        return null
    }

    /**
     * Asked of PDFs ONLY. PdfRenderer has to seek, but the image path reads streams end to end -
     * the probe, telephoto's content-URI source and Coil all open their own - so demanding a
     * descriptor there would reject pipe-backed providers whose images render perfectly well.
     */
    private suspend fun validateSeekable(streamed: ViewerSource.Streamed): Throwable? {
        if (contentReader.openReadPfd(streamed)?.use { true } == true) return null
        log(tag, WARN) { "$streamed is a PDF from a provider that cannot seek" }
        return ViewerContentUnreadableException(streamed.displayName)
    }

    data class State(
        val content: ViewerContent = ViewerContent.Loading,
        val fileInfo: ViewerFileInfo? = null,
        /**
         * The lookup [fileInfo] was built from. Retained because the clipboard stores lookups, not
         * paths - keeping this one spares a second gateway round trip when the user copies or cuts.
         */
        val lookup: APathLookup<*>? = null,
        /**
         * The file this viewer is bound to and would render: the symlink target where there is one
         * and [lookup] otherwise. Set even when nothing is on display - an unsupported, undecodable
         * or empty file still has one, so bytes that arrive later can be noticed. Doubles as the
         * baseline [checkExternalChange] compares against.
         */
        val contentLookup: APathLookup<*>? = null,
        /** What the last probe found on disk, or null while the file still matches what was loaded. */
        val externalChange: ViewerExternalChange? = null,
    )

    @AssistedFactory
    interface Factory : WorkspaceFactory<ViewerArguments> {
        override fun create(id: Workspace.Id, arguments: ViewerArguments): ViewerWorkspace

        override val argumentsSerializer: KSerializer<ViewerArguments> get() = serializer()

        override fun deriveDisplay(arguments: ViewerArguments): WorkspaceDisplay =
            deriveViewerDisplay(arguments)
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.VIEWER)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}

internal fun compareVersions(apkVersionCode: Long, installedVersionCode: Long): VersionComparison = when {
    apkVersionCode > installedVersionCode -> VersionComparison.APK_NEWER
    apkVersionCode < installedVersionCode -> VersionComparison.INSTALLED_NEWER
    else -> VersionComparison.SAME
}

private val VECTOR_IMAGE_MIME_TYPES = setOf("image/svg+xml")

/**
 * Vector images have no pixel dimensions to read, so an empty dimension probe means something very
 * different for them than it does for a raster format.
 */
private val MimeInfo.isVectorImage: Boolean
    get() = rawType in VECTOR_IMAGE_MIME_TYPES

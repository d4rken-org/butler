package eu.darken.butler

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.BuildWrap
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.AutomaticBugReporter
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.bugreport.BugReportRecorder
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.common.debug.logging.LogCatLogger
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.RingLogBuffer
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logviewer.core.LogHistoryRecorder
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.theming.Theming
import eu.darken.butler.common.trash.TrashCleanupScheduler
import eu.darken.butler.common.updater.UpdateService
import eu.darken.butler.main.core.CurriculumVitae
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.external.ExternalImportSweeper
import eu.darken.butler.main.core.release.ReleaseManager
import eu.darken.butler.main.core.operations.fgs.OperationFgsCoordinator
import eu.darken.butler.main.core.shortcuts.DynamicShortcutManager
import eu.darken.butler.provider.documents.core.DocumentsProviderManager
import eu.darken.butler.workspace.core.operations.history.OperationHistoryRepo
import eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import eu.darken.butler.workspace.core.WorkspaceAutoPauseManager
import eu.darken.butler.workspace.core.WorkspaceRegistryValidator
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import kotlin.system.exitProcess

@HiltAndroidApp
open class App : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject @AppScope lateinit var appScope: CoroutineScope
    @Inject lateinit var dispatcherProvider: DispatcherProvider
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var bugReporter: AutomaticBugReporter
    @Inject lateinit var generalSettings: GeneralSettings
    @Inject lateinit var bugReportRecorder: BugReportRecorder
    @Inject lateinit var debugSettings: DebugSettings
    @Inject lateinit var curriculumVitae: CurriculumVitae
    @Inject lateinit var updateService: UpdateService
    @Inject lateinit var theming: Theming
    @Inject lateinit var releaseManager: ReleaseManager
    @Inject lateinit var shortcutManager: DynamicShortcutManager
    @Inject lateinit var safLocationManager: SAFLocationManager
    @Inject lateinit var imageLoaderProvider: Provider<ImageLoader>
    @Inject lateinit var workspacePreviewManager: WorkspacePreviewManager
    @Inject lateinit var documentsProviderManager: DocumentsProviderManager
    @Inject lateinit var trashCleanupScheduler: TrashCleanupScheduler
    @Inject lateinit var workspaceRegistryValidator: WorkspaceRegistryValidator
    @Inject lateinit var ringLogBuffer: RingLogBuffer
    @Inject lateinit var logHistoryRecorder: LogHistoryRecorder
    @Inject lateinit var bugReportRepo: BugReportRepo

    /**
     * Lazy because Hilt singletons are otherwise constructed only on first call-site injection.
     * Calling .get() in onCreate() forces construction so the repo's init block subscribes to
     * OperationsManager.completedOperations from app start — otherwise early operation completions
     * would be missed.
     */
    @Inject lateinit var operationHistoryRepo: dagger.Lazy<OperationHistoryRepo>

    /** Eagerly constructed so the operation foreground-service/notifications observe from app start. */
    @Inject lateinit var operationFgsCoordinator: dagger.Lazy<OperationFgsCoordinator>

    /**
     * Nothing else injects it, so without an eager .get() its evaluation loop would never start and
     * idle tabs would never be paused.
     */
    @Inject lateinit var workspaceAutoPauseManager: dagger.Lazy<WorkspaceAutoPauseManager>

    /** Same deal: nothing else injects it, so its sweep loop needs an eager .get() to ever run. */
    @Inject lateinit var externalImportSweeper: dagger.Lazy<ExternalImportSweeper>

    private val logCatLogger = LogCatLogger()

    override fun onCreate() {
        super.onCreate()
        // Always-on, before anything else: retains recent log lines in memory so a crash or
        // Bugs.report can attach the trail leading up to it, even in release builds.
        Logging.install(ringLogBuffer)

        if (BuildConfigWrap.DEBUG) {
            // LogCatLogger is installed by the reactive combine block below (the single owner);
            // installing it here too would double up logcat output on debug cold start.
            log(TAG) { "BuildConfigWrap.DEBUG=true" }
            workspaceRegistryValidator.validate()
        }
        log(TAG) { "Fingerprint: ${BuildWrap.FINGERPRINT}" }

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Synchronously persist a crash report (process is dying). Everything here is best-effort
            // and behind catch(Throwable) — even asLog()/snapshot allocation can fail under OOM, and
            // crash propagation to oldHandler must never be blocked by our reporting.
            try {
                log(TAG, ERROR) { "UNCAUGHT EXCEPTION: ${throwable.asLog()}" }
                bugReportRepo.captureCrashBlocking(throwable, thread)
            } catch (_: Throwable) {
            }
            if (oldHandler != null) oldHandler.uncaughtException(thread, throwable) else exitProcess(1)
            Thread.sleep(100)
        }

        combine(
            debugSettings.isDebugMode.flow,
            debugSettings.isTraceMode.flow,
            bugReportRecorder.state,
        ) { isDebug, isTrace, recorderState ->
            log(TAG) { "isDebug=$isDebug, isTrace=$isTrace, isRecording=${recorderState.isRecording}" }

            if (isDebug) {
                Logging.install(logCatLogger)
            } else {
                Logging.remove(logCatLogger)
            }

            // Capture more detail into the in-memory buffer when the cost is already accepted.
            val verbose = isDebug || recorderState.isRecording
            ringLogBuffer.setThreshold(if (verbose) DEBUG else RingLogBuffer.DEFAULT_THRESHOLD)

            // Global capture floor for the shared log-viewer buffer (floating panel + Developer
            // LOGS tab). Owned here, not by the viewer UIs — a panel-level selection is display-only.
            logHistoryRecorder.setMinPriority(if (verbose) VERBOSE else DEBUG)

            Bugs.isDebug = isDebug || recorderState.isRecording
            Bugs.isTrace = isDebug && isTrace
        }.launchIn(appScope)

        // Route manual Bugs.report(...) calls to the local reporter (stores a report on-device).
        Bugs.reporter = bugReporter
        bugReporter.setup(this)

        bugReportRecorder.state
            .onEach { log(TAG) { "RecorderState: $it" } }
            .launchIn(appScope)

        theming.setup()

        appScope.launch {
            curriculumVitae.updateAppLaunch()
            releaseManager.checkEarlyAdopter()
        }

        shortcutManager.initialize()

        workspacePreviewManager.start()

        // Eagerly construct OperationHistoryRepo so it subscribes to completedOperations from start.
        operationHistoryRepo.get()

        // Eagerly start the operation foreground-service/notification coordinator.
        val fgsCoordinator = operationFgsCoordinator.get()
        fgsCoordinator.start()

        trashCleanupScheduler.setup()

        // Eagerly start the idle-tab auto-pause evaluation loop.
        val autoPauseManager = workspaceAutoPauseManager.get()

        // Frees cache copies of files other apps shared with us once no workspace holds them.
        externalImportSweeper.get().start()

        // One-shot cleanup of the retired external/internal debug-log store (recordings now live in
        // the unified bug-report store under filesDir). Idempotent and harmless if already gone.
        appScope.launch(dispatcherProvider.IO) {
            runCatching { File(externalCacheDir, "debug/logs").deleteRecursively() }
            runCatching { File(cacheDir, "debug/logs").deleteRecursively() }
            runCatching { File(getExternalFilesDir(null), "force_debug_run").delete() }
        }

        // Drive operation notifications by app foreground/background, and refresh SAF on foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                log(TAG) { "App foregrounded" }
                fgsCoordinator.onAppForegrounded()
                autoPauseManager.onAppForegrounded()
                appScope.launch {
                    safLocationManager.refresh()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                log(TAG) { "App backgrounded" }
                fgsCoordinator.onAppBackgrounded()
            }
        })

        log(TAG) { "onCreate() done! ${Exception().asLog()}" }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(
                when {
                    BuildConfigWrap.DEBUG -> android.util.Log.VERBOSE
                    BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.DEV -> android.util.Log.DEBUG
                    BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.BETA -> android.util.Log.INFO
                    BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.RELEASE -> android.util.Log.WARN
                    else -> android.util.Log.VERBOSE
                }
            )
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoaderProvider.get()

    companion object {
        internal val TAG = logTag("App")
    }
}

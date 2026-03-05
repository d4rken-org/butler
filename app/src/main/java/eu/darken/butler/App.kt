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
import eu.darken.butler.common.debug.logging.LogCatLogger
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.recorder.core.DebugSessionManager
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.theming.Theming
import eu.darken.butler.common.trash.TrashCleanupScheduler
import eu.darken.butler.common.updater.UpdateService
import eu.darken.butler.main.core.CurriculumVitae
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.release.ReleaseManager
import eu.darken.butler.main.core.shortcuts.DynamicShortcutManager
import eu.darken.butler.provider.documents.core.DocumentsProviderManager
import eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
    @Inject lateinit var sessionManager: DebugSessionManager
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

    private val logCatLogger = LogCatLogger()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfigWrap.DEBUG) {
            Logging.install(logCatLogger)
            log(TAG) { "BuildConfigWrap.DEBUG=true" }
        }
        log(TAG) { "Fingerprint: ${BuildWrap.FINGERPRINT}" }

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log(TAG, ERROR) { "UNCAUGHT EXCEPTION: ${throwable.asLog()}" }
            if (oldHandler != null) oldHandler.uncaughtException(thread, throwable) else exitProcess(1)
            Thread.sleep(100)
        }

        combine(
            debugSettings.isDebugMode.flow,
            debugSettings.isTraceMode.flow,
            sessionManager.state,
        ) { isDebug, isTrace, sessState ->
            log(TAG) { "isDebug=$isDebug, isTrace=$isTrace, activeSession=${sessState.activeSession != null}" }

            if (isDebug) {
                Logging.install(logCatLogger)
            } else {
                Logging.remove(logCatLogger)
            }

            Bugs.isDebug = isDebug || sessState.activeSession != null
            Bugs.isTrace = isDebug && isTrace
        }.launchIn(appScope)

        bugReporter.setup(this)

        sessionManager.state
            .onEach { log(TAG) { "SessionManager: $it" } }
            .launchIn(appScope)

        theming.setup()

        appScope.launch {
            curriculumVitae.updateAppLaunch()
            releaseManager.checkEarlyAdopter()
        }

        shortcutManager.initialize()

        workspacePreviewManager.start()

        trashCleanupScheduler.setup()

        // Automatically refresh SAF permissions when app comes to foreground
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                log(TAG) { "App foregrounded, refreshing SAF permissions" }
                appScope.launch {
                    safLocationManager.refresh()
                }
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

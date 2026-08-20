package eu.darken.butler.main.ui

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.compose.LocalAvoidDisplayCutout
import eu.darken.butler.common.compose.LocalUserActivity
import eu.darken.butler.common.compose.UserActivityTracker
import eu.darken.butler.common.compose.tour.GuidedTourController
import eu.darken.butler.common.compose.tour.GuidedTourHost
import eu.darken.butler.common.compose.tour.LocalGuidedTourController
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.debug.logviewer.ui.FloatingLogPanelHost
import eu.darken.butler.common.debug.recorder.ui.banner.RecordingBannerHost
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.LocalNavigationController
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.NavigationDestination
import eu.darken.butler.common.navigation.NavigationEntry
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.navigation.onboarding
import eu.darken.butler.common.theming.ButlerTheme
import eu.darken.butler.common.ui.Activity2
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.main.core.CurriculumVitae
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.operations.fgs.ACTION_FOCUS_OPERATION
import eu.darken.butler.main.core.operations.fgs.EXTRA_OPERATION_ID
import eu.darken.butler.main.core.operations.fgs.EXTRA_WORKSPACE_ID
import eu.darken.butler.main.core.operations.fgs.NotificationPermissionGate
import eu.darken.butler.main.core.operations.fgs.OperationFgsCoordinator
import eu.darken.butler.main.core.external.ShareRoute
import eu.darken.butler.main.core.external.resolveShareRoute
import eu.darken.butler.main.core.shortcuts.DynamicShortcutManager
import eu.darken.butler.main.ui.external.ExternalOpenDialog
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.workspaces.workspaces
import javax.inject.Inject
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import eu.darken.butler.common.R as CommonR

@AndroidEntryPoint
class MainActivity : Activity2() {

    private val vm: MainViewModel by viewModels()

    @Inject lateinit var curriculumVitae: CurriculumVitae
    @Inject lateinit var navCtrl: NavigationController
    @Inject lateinit var navigationEntries: Set<@JvmSuppressWildcards NavigationEntry>
    @Inject lateinit var shortcutManager: DynamicShortcutManager
    @Inject lateinit var notificationPermissionGate: NotificationPermissionGate
    @Inject lateinit var operationFgsCoordinator: OperationFgsCoordinator
    @Inject lateinit var guidedTourController: GuidedTourController
    @Inject lateinit var userActivityTracker: UserActivityTracker

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        log(TAG) { "POST_NOTIFICATIONS granted=$granted" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set initial window background to prevent white flash
        // This will be updated once the Compose theme is loaded
        window.decorView.setBackgroundColor(
            if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
                0xFF0E1514.toInt() // Dark background
            } else {
                0xFFF4FBF8.toInt() // Light background
            }
        )

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (BuildConfigWrap.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        curriculumVitae.updateAppOpened()

        // Just-in-time POST_NOTIFICATIONS request when a background operation needs notifications.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                notificationPermissionGate.prompts.collect {
                    if (hasApiLevel(33)) {
                        log(TAG) { "Requesting POST_NOTIFICATIONS (background operation running)" }
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }

        // Handle shortcut intent if present (will be processed once navigation is ready).
        // Only on a fresh start: a recreation (e.g. rotation) hands us the original intent again,
        // which would replay an arrival the user already dealt with.
        if (savedInstanceState == null) savedIntent = intent

        setContent {
            // Prime WindowInsets listener before first layout to prevent UI jumping.
            // Without this, nested composables get 0 insets on first composition.
            // See: https://github.com/google/accompanist/issues/155
            val primedInsets = WindowInsets.safeDrawing
            LaunchedEffect(Unit) {
                log(TAG) { "WindowInsets primed: $primedInsets" }
            }

            val themeState by vm.themeState.collectAsState()
            val vmState by vm.state.collectAsState(initial = null)

            LaunchedEffect(themeState) {
                log(TAG) { "Theme state: $themeState" }
            }
            ButlerTheme(state = themeState) {
                // Set window background to match the current theme
                val backgroundColor = MaterialTheme.colorScheme.background
                LaunchedEffect(backgroundColor) {
                    window.decorView.setBackgroundColor(backgroundColor.toArgb())
                }

                CompositionLocalProvider(
                    LocalNavigationController provides navCtrl,
                    LocalUserActivity provides userActivityTracker,
                ) {
                    ErrorEventHandler(vm)
                    NavigationEventHandler(vm)

                    vmState?.let { mainState ->
                        LaunchedEffect(mainState) {
                            log(TAG) { "Main state: $mainState" }
                        }
                        CompositionLocalProvider(
                            LocalAvoidDisplayCutout provides mainState.avoidDisplayCutout,
                        ) {
                            Navigation(mainState)

                            // During onboarding the arrival stays pending: the dialog shows up once
                            // the user is through and the workspace UI can actually take the file.
                            val externalOpen by vm.externalOpen.collectAsState()
                            externalOpen
                                ?.takeIf { mainState.startScreen == MainViewModel.State.StartScreen.HOME }
                                ?.let { arrival ->
                                    ExternalOpenDialog(
                                        displayName = arrival.displayName,
                                        mime = arrival.mime,
                                        sizeBytes = arrival.sizeBytes,
                                        previewUri = arrival.originalUri.takeIf { arrival.mime.isImage },
                                        options = arrival.options,
                                        onOption = { vm.onExternalOpenAction(it) },
                                        onDismiss = { vm.onExternalOpenDismiss() },
                                    )
                                }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        savedIntent = intent
        // A singleTask activity that is already resumed gets onNewIntent but no following onResume,
        // so process here too. handleSavedIntent() is idempotent (consumes savedIntent).
        handleSavedIntent()
    }

    @Composable
    private fun Navigation(state: MainViewModel.State) {
        val start = remember {
            when (state.startScreen) {
                MainViewModel.State.StartScreen.ONBOARDING -> Nav.Main.onboarding()
                MainViewModel.State.StartScreen.HOME -> Nav.Main.workspaces()
            }
        }

        val backStack = rememberNavBackStack(start)
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(Unit) { navCtrl.setup(backStack) }

        // Tours without click protection end themselves when the user navigates away.
        LaunchedEffect(backStack.lastOrNull()) {
            guidedTourController.onRouteChanged(backStack.lastOrNull())
        }

        // Handle system back button with double-press to exit
        BackHandler(enabled = backStack.size <= 1) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < BACK_PRESS_INTERVAL) {
                // Second press within interval - exit
                log(TAG) { "Double back press detected, exiting app" }
                finish()
            } else {
                // First press - show toast and update timestamp
                log(TAG) { "First back press, showing toast" }
                lastBackPressTime = currentTime
                Toast.makeText(
                    this@MainActivity,
                    CommonR.string.general_press_back_again_to_exit,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // The tour host wraps only the nav content: its scrim and cut-out are anchored in root
            // coordinates, and the banner/log panel below stay later siblings so those debug and
            // system affordances keep drawing above the scrim.
            CompositionLocalProvider(LocalGuidedTourController provides guidedTourController) {
                GuidedTourHost(
                    session = guidedTourController.session,
                    onNext = { tourId, stepId ->
                        coroutineScope.launch { guidedTourController.next(tourId, stepId) }
                    },
                    onPrevious = { coroutineScope.launch { guidedTourController.previous() } },
                    onDontShowAgain = { coroutineScope.launch { guidedTourController.dismissForever() } },
                    onDisableAllTours = { coroutineScope.launch { guidedTourController.disableAllTours() } },
                    onStepRendered = guidedTourController::markStepRendered,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = {
                            // Only handle programmatic navigation
                            navCtrl.up()
                        },
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        entryProvider = entryProvider {
                            navigationEntries.forEach { entry ->
                                entry.apply {
                                    log(TAG) { "Set up navigation entry: $this" }
                                    setup()
                                }
                            }
                        }
                    )
                }
            }

            // App-wide recording banner overlay
            RecordingBannerHost(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding(),
            )

            // Debug-only floating log panel; last child so it draws above nav content and the
            // banner. Handles safeDrawing insets itself for full-screen drag bounds.
            FloatingLogPanelHost(modifier = Modifier.fillMaxSize())
        }
    }

    override fun onResume() {
        super.onResume()
        vm.checkUpgrades()
        handleSavedIntent()
        // Returning to the app counts as activity, even without touching anything yet.
        userActivityTracker.onUserInteraction()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        userActivityTracker.onUserInteraction()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Still foreground-eligible here, so this is the legal moment to acquire the operation
        // foreground service before the app is fully backgrounded.
        operationFgsCoordinator.onAppBackgrounded()
    }

    private fun handleSavedIntent() {
        // Consume once: clearing up-front keeps this idempotent across the onNewIntent + onResume pair.
        val intent = savedIntent?.also { savedIntent = null } ?: return
        when (intent.action) {
            DynamicShortcutManager.EXPLORER_SHORTCUT_ACTION -> handleShortcutIntent(intent)
            DynamicShortcutManager.EXPLORER_NEW_ACTION -> handleNewExplorerIntent()
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> handleShareIntent(intent)
            Intent.ACTION_VIEW -> handleViewIntent(intent)
            ACTION_FOCUS_OPERATION -> handleFocusOperationIntent(intent)
        }
    }

    private fun handleFocusOperationIntent(intent: Intent) {
        val workspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID)
            ?.let { runCatching { Workspace.Id(Uuid.parse(it)) }.getOrNull() }
        if (workspaceId == null) {
            log(TAG, WARN) { "FOCUS_OPERATION intent without a valid workspace id" }
            return
        }
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID)
            ?.let { runCatching { Operation.Id(Uuid.parse(it)) }.getOrNull() }
        log(TAG) { "Focusing workspace $workspaceId for operation $operationId" }
        vm.focusOperationWorkspace(workspaceId, operationId)
    }

    private fun handleShortcutIntent(intent: Intent) {
        val serializedPath = intent.getStringExtra(DynamicShortcutManager.EXPLORER_EXTRA_PATH)
        if (serializedPath != null) {
            log(TAG) { "Opening directory from shortcut: $serializedPath" }
            vm.openDirectoryFromShortcut(serializedPath)
            shortcutManager.reportPathShortcutUsed(serializedPath)
        }
    }

    private fun handleNewExplorerIntent() {
        log(TAG) { "Creating new Explorer workspace from shortcut" }
        vm.createNewExplorerWorkspace()
        shortcutManager.reportNewExplorerShortcutUsed()
    }

    private fun handleViewIntent(intent: Intent) {
        val uri = intent.data
        if (uri == null) {
            log(TAG, WARN) { "VIEW intent received but no data URI found" }
            return
        }
        log(TAG) { "Handling VIEW intent with URI: $uri (type: ${intent.type})" }
        when (intent.type) {
            MIME_DOCUMENT_ROOT, MIME_DOCUMENT_DIRECTORY -> vm.openFromDocumentUri(uri)
            else -> vm.onExternalFile(
                uri = uri,
                intentType = intent.type,
                callerPackage = intent.`package` ?: referrer?.host,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }
        val route = resolveShareRoute(
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
            uris = uris,
        )
        // The route itself is not logged: shared text is the user's content.
        log(TAG) { "Handling share intent as ${route::class.simpleName} with ${uris.size} URI(s)" }

        when (route) {
            is ShareRoute.Text -> vm.createEditorWorkspaceWithText(route.text, route.subject)

            // A single file goes through the arrival dialog, the same one "Open with" uses, so the
            // user gets View and Show in Explorer instead of only ever landing in "Save as".
            is ShareRoute.SingleFile -> vm.onExternalFile(
                uri = route.uri,
                intentType = intent.type,
                callerPackage = intent.`package` ?: referrer?.host,
                caption = route.caption,
            )

            is ShareRoute.MultipleFiles -> vm.createSaverWorkspace(
                sourceUris = route.uris,
                callerPackage = intent.`package` ?: referrer?.host,
            )

            ShareRoute.Nothing -> log(TAG, WARN) { "Share intent received but no content found" }
        }
    }

    private var savedIntent: Intent? = null
    private var lastBackPressTime: Long = 0

    companion object {
        private val TAG = logTag("Main", "Activity")
        private const val BACK_PRESS_INTERVAL = 2000L // 2 seconds
        private const val MIME_DOCUMENT_ROOT = "vnd.android.document/root"
        private const val MIME_DOCUMENT_DIRECTORY = "vnd.android.document/directory"
    }
}

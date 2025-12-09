package eu.darken.butler.main.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.NavigationDestination
import eu.darken.butler.common.navigation.NavigationEntry
import eu.darken.butler.common.navigation.onboarding
import eu.darken.butler.common.theming.MyAppTheme
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.ui.Activity2
import eu.darken.butler.main.core.CurriculumVitae
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.shortcuts.DynamicShortcutManager
import eu.darken.butler.workspace.ui.workspaces.workspaces
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : Activity2() {

    private val vm: MainViewModel by viewModels()

    @Inject lateinit var curriculumVitae: CurriculumVitae
    @Inject lateinit var navCtrl: NavigationController
    @Inject lateinit var navigationEntries: Set<@JvmSuppressWildcards NavigationEntry>
    @Inject lateinit var generalSettings: GeneralSettings
    @Inject lateinit var shortcutManager: DynamicShortcutManager

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

        // Handle shortcut intent if present (will be processed once navigation is ready)
        savedIntent = intent

        setContent {
            val themeState by produceState<ThemeState?>(initialValue = null) {
                vm.themeState.collect { value = it }
            }
            val vmState by produceState<MainViewModel.State?>(initialValue = null) {
                vm.state.collect { value = it }
            }
            themeState?.let { themeState ->
                log(TAG) { "Theme state: $themeState" }
                MyAppTheme(state = themeState) {
                    // Set window background to match the current theme
                    val backgroundColor = MaterialTheme.colorScheme.background
                    LaunchedEffect(backgroundColor) {
                        window.decorView.setBackgroundColor(backgroundColor.toArgb())
                    }

                    ErrorEventHandler(vm)

                    vmState?.let { mainState ->
                        log(TAG) { "Main state: $mainState" }
                        Navigation(mainState)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        savedIntent = intent
    }

    @Composable
    private fun Navigation(state: MainViewModel.State) {
        val start = remember {
            when (state.startScreen) {
                MainViewModel.State.StartScreen.ONBOARDING -> Nav.Main.onboarding()
                MainViewModel.State.StartScreen.HOME -> Nav.Main.workspaces()
            }
        }

        val backStack = rememberNavBackStack<NavigationDestination>(start)

        LaunchedEffect(Unit) { navCtrl.setup(backStack) }

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

        NavDisplay(
            backStack = backStack,
            onBack = {
                // Only handle programmatic navigation
                navCtrl.up()
            },
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

    override fun onResume() {
        super.onResume()
        vm.checkUpgrades()

        savedIntent?.let { intent ->
            when (intent.action) {
                DynamicShortcutManager.EXPLORER_SHORTCUT_ACTION -> {
                    handleShortcutIntent(intent)
                    savedIntent = null
                }
                DynamicShortcutManager.EXPLORER_NEW_ACTION -> {
                    handleNewExplorerIntent()
                    savedIntent = null
                }
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                    handleShareIntent(intent)
                    savedIntent = null
                }
            }
        }
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

    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent) {
        // Check for text content first (e.g., shared text from other apps)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null) {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            log(TAG) { "Handling text share: ${text.length} chars, subject=$subject" }
            vm.createEditorWorkspaceWithText(text, subject)
            return
        }

        // Fall through to file handling
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }

        if (uris.isEmpty()) {
            log(TAG) { "Share intent received but no content found" }
            return
        }

        log(TAG) { "Handling share intent with ${uris.size} URI(s): $uris" }

        vm.createSaverWorkspace(
            sourceUris = uris,
            callerPackage = intent.`package` ?: referrer?.host,
        )
    }

    private var savedIntent: Intent? = null
    private var lastBackPressTime: Long = 0

    companion object {
        private val TAG = logTag("Main", "Activity")
        private const val BACK_PRESS_INTERVAL = 2000L // 2 seconds
    }
}

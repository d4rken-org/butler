package eu.darken.butler.main.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
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

        // Handle system back button
        BackHandler(enabled = backStack.size <= 1) {
            // We're at the root, check if we should show confirmation
            vm.isConfirmExitEnabled { isEnabled ->
                if (isEnabled) {
                    vm.setShowExitConfirmation(true)
                } else {
                    finish()
                }
            }
        }

        if (state.showExitConfirmation) {
            ExitConfirmationDialog(
                onDismiss = { vm.setShowExitConfirmation(false) },
                onConfirm = {
                    if (state.dontAskAgain) {
                        vm.updateConfirmExitEnabled(false)
                    }
                    finish()
                },
                dontAskAgain = state.dontAskAgain,
                onDontAskAgainChange = { vm.setDontAskAgain(it) }
            )
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
            }
        }
    }

    private fun handleShortcutIntent(intent: Intent) {
        val directoryPath = intent.getStringExtra(DynamicShortcutManager.EXPLORER_EXTRA_PATH)
        if (directoryPath != null) {
            log(TAG) { "Opening directory from shortcut: $directoryPath" }
            vm.openDirectoryFromShortcut(directoryPath)
            shortcutManager.reportPathShortcutUsed(directoryPath)
        }
    }

    private fun handleNewExplorerIntent() {
        log(TAG) { "Creating new Explorer workspace from shortcut" }
        vm.createNewExplorerWorkspace()
        shortcutManager.reportNewExplorerShortcutUsed()
    }

    private var savedIntent: Intent? = null

    companion object {
        private val TAG = logTag("Main", "Activity")
    }
}

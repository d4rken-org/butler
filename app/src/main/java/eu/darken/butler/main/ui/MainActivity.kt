package eu.darken.butler.main.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.navigation3.runtime.entry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.butler.common.compose.SampleContent
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.asErrorDialogBuilder
import eu.darken.butler.common.theming.MyAppTheme
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.uix.Activity2
import eu.darken.butler.main.core.CurriculumVitae
import eu.darken.butler.main.ui.onboarding.OnboardingScreenHost
import eu.darken.butler.main.ui.settings.SettingsScreen
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : Activity2() {

    private val vm: MainViewModel by viewModels()

    @Inject lateinit var curriculumVitae: CurriculumVitae

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        curriculumVitae.updateAppOpened()

        vm.errorEvents.observe {
            log(tag, VERBOSE) { "Error event: $it" }
            it.asErrorDialogBuilder(this).show()
        }

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
        val start = when (state.startScreen) {
            MainViewModel.State.StartScreen.ONBOARDING -> Destination.Onboarding
            MainViewModel.State.StartScreen.HOME -> Destination.Home
        }

        val backStack = rememberNavBackStack(start)

        LaunchedEffect(state.startScreen) {
            backStack.clear()
            backStack.add(start)
        }

        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<Destination.Home> {
                    SampleContent("Home screen (only accessible after onboarding)")
                }
                entry<Destination.Onboarding> {
                    OnboardingScreenHost()
                }
                entry<Destination.Settings> {
                    SettingsScreen(
                        onNavigateUp = { backStack.removeLastOrNull() },
                        onNavigateToGeneral = {  },
                        onNavigateToSupport = {  },
                        onChangelogClick = {
                            // TODO: Handle changelog click
                        }
                    )
                }
                entry<Destination.Settings.General> {
                    SampleContent("Settings.General")
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        vm.checkUpgrades()
    }

    companion object {
        private val TAG = logTag("Main", "Activity")
    }
}

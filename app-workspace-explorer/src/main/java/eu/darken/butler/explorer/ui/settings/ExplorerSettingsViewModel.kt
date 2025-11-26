package eu.darken.butler.explorer.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.recyclebin.RecycleBinSettings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.SortSettings
import javax.inject.Inject
import kotlin.time.Duration.Companion.days

@HiltViewModel
class ExplorerSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    navigationController: NavigationController,
    private val explorerSettings: ExplorerSettings,
    private val recycleBinSettings: RecycleBinSettings,
) : ViewModel4(dispatcherProvider, logTag("Explorer", "Settings", "Screen", "VM"), navigationController) {

    val state = combine(
        explorerSettings.sortSettings.flow,
        explorerSettings.useRegexPatterns.flow,
        explorerSettings.useBackButtonForNavigation.flow,
        recycleBinSettings.enabled.flow,
        recycleBinSettings.expiresAfter.flow,
        recycleBinSettings.maxRecycleBinSize.flow,
    ) { sortSettings, useRegexPatterns, useBackButtonForNavigation, recycleBinEnabled, expiresAfter, maxSize ->
        State(
            sortSettings = sortSettings,
            useRegexPatterns = useRegexPatterns,
            useBackButtonForNavigation = useBackButtonForNavigation,
            recycleBinEnabled = recycleBinEnabled,
            recycleBinAutoDeleteDays = expiresAfter.inWholeDays.toInt(),
            recycleBinMaxSizeMB = maxSize / 1048576L,
        )
    }.asStateFlow()

    fun toggleRegexPatterns(enabled: Boolean) = launch {
        explorerSettings.useRegexPatterns.value(enabled)
    }

    fun toggleBackButtonNavigation(enabled: Boolean) = launch {
        explorerSettings.useBackButtonForNavigation.value(enabled)
    }

    fun toggleRecycleBin(enabled: Boolean) = launch {
        log(tag) { "toggleRecycleBin($enabled)" }
        recycleBinSettings.enabled.value(enabled)
    }

    fun setRecycleBinAutoDeleteDays(days: Int) = launch {
        log(tag) { "setRecycleBinAutoDeleteDays($days)" }
        recycleBinSettings.expiresAfter.value(days.days)
    }

    fun setRecycleBinMaxSizeMB(sizeMB: Long) = launch {
        log(tag) { "setRecycleBinMaxSizeMB($sizeMB)" }
        recycleBinSettings.maxRecycleBinSize.value(sizeMB * 1048576L)
    }

    data class State(
        val sortSettings: SortSettings,
        val useRegexPatterns: Boolean,
        val useBackButtonForNavigation: Boolean,
        val recycleBinEnabled: Boolean = true,
        val recycleBinAutoDeleteDays: Int = 30,
        val recycleBinMaxSizeMB: Long = 500L,
    )
}
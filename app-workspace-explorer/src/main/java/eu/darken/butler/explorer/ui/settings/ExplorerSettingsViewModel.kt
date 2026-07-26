package eu.darken.butler.explorer.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.SortSettings
import javax.inject.Inject
import kotlin.time.Duration.Companion.days

@HiltViewModel
class ExplorerSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val explorerSettings: ExplorerSettings,
    private val trashSettings: TrashSettings,
) : ViewModel4(dispatcherProvider, logTag("Explorer", "Settings", "Screen", "VM")) {

    val state = combine(
        explorerSettings.sortSettings.flow,
        explorerSettings.useRegexPatterns.flow,
        explorerSettings.useBackButtonForNavigation.flow,
        trashSettings.enabled.flow,
        trashSettings.expiresAfter.flow,
        trashSettings.maxTrashSize.flow,
    ) { sortSettings, useRegexPatterns, useBackButtonForNavigation, recycleBinEnabled, expiresAfter, maxSize ->
        State(
            sortSettings = sortSettings,
            useRegexPatterns = useRegexPatterns,
            useBackButtonForNavigation = useBackButtonForNavigation,
            trashEnabled = recycleBinEnabled,
            trashAutoDeleteDays = expiresAfter.inWholeDays.toInt(),
            trashMaxSizeMB = maxSize / 1048576L,
        )
    }.asStateFlow()

    fun toggleRegexPatterns(enabled: Boolean) = launch {
        explorerSettings.useRegexPatterns.value(enabled)
    }

    fun toggleBackButtonNavigation(enabled: Boolean) = launch {
        explorerSettings.useBackButtonForNavigation.value(enabled)
    }

    fun toggleTrash(enabled: Boolean) = launch {
        log(tag) { "toggleTrash($enabled)" }
        trashSettings.enabled.value(enabled)
    }

    fun setTrashAutoDeleteDays(days: Int) = launch {
        log(tag) { "setTrashAutoDeleteDays($days)" }
        trashSettings.expiresAfter.value(days.days)
    }

    fun setTrashMaxSizeMB(sizeMB: Long) = launch {
        log(tag) { "setTrashMaxSizeMB($sizeMB)" }
        trashSettings.maxTrashSize.value(sizeMB * 1048576L)
    }

    data class State(
        val sortSettings: SortSettings,
        val useRegexPatterns: Boolean,
        val useBackButtonForNavigation: Boolean,
        val trashEnabled: Boolean = true,
        val trashAutoDeleteDays: Int = 30,
        val trashMaxSizeMB: Long = 500L,
    )
}
package eu.darken.butler.setup.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupManager
import eu.darken.butler.setup.core.SetupModule
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = SetupViewModel.Factory::class)
class SetupViewModel @AssistedInject constructor(
    @Assisted private val options: SetupScreenOptions,
    dispatcherProvider: DispatcherProvider,
    private val setupManager: SetupManager,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatcherProvider, logTag("Setup", "ViewModel")) {

    private val _permissionRequestEvents = MutableSharedFlow<android.content.Intent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val permissionRequestEvents = _permissionRequestEvents.asSharedFlow()

    private val _runtimePermissionEvents = MutableSharedFlow<Set<String>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val runtimePermissionEvents = _runtimePermissionEvents.asSharedFlow()

    val state = setupManager.moduleStates
        .map { moduleStates -> buildState(moduleStates) }
        .asStateFlow()

    private fun buildState(moduleStates: Map<SetupModule.Type, SetupModule.State>): State {
        val items = SetupModule.Type.entries.mapNotNull { type ->
            if (options.typeFilter != null && type !in options.typeFilter) {
                return@mapNotNull null
            }

            val state = moduleStates[type]
            if (state != null) {
                if (!options.showCompleted && state is SetupModule.State.Current && state.isComplete) {
                    return@mapNotNull null
                }

                SetupItem(
                    type = type,
                    state = state,
                    isRequired = options.satisfyingCombos?.all { combo -> combo.contains(type) } ?: false,
                    priority = type.priority,
                )
            } else {
                log(tag) { "No state found for setup type: $type" }
                null
            }
        }.sortedWith(
            compareBy(
                { (it.state as? SetupModule.State.Current)?.isComplete == true },
                { it.priority },
            )
        )

        val allRequiredComplete = options.satisfyingCombos?.let { combos ->
            combos.isNotEmpty() && combos.any { combo ->
                combo.all { type ->
                    val moduleState = moduleStates[type]
                    (moduleState as? SetupModule.State.Current)?.isComplete == true
                }
            }
        } ?: false

        return State(
            items = items,
            allRequiredComplete = allRequiredComplete,
        )
    }

    init {
        log(tag) { "init($this) with options: $options" }
    }

    fun refresh() = launch {
        log(tag) { "refresh()" }
        setupManager.refresh()
    }

    fun executeAction(type: SetupModule.Type, action: SetupAction) = launch {
        log(tag) { "executeAction(type=$type, action=$action)" }

        val result = setupManager.executeAction(type, action)
        when {
            result?.intent != null -> {
                log(tag) { "Emitting permission request intent for $type" }
                _permissionRequestEvents.emit(result.intent)
            }
            result?.runtimePermissions?.isNotEmpty() == true -> {
                log(tag) { "Emitting runtime permission request for $type: ${result.runtimePermissions}" }
                _runtimePermissionEvents.emit(result.runtimePermissions)
            }
        }
    }

    fun openHelp(type: SetupModule.Type) = launch {
        log(tag) { "openHelp(type=$type)" }
        val helpUrl = getHelpUrl(type)
        webpageTool.open(helpUrl)
    }

    private fun getHelpUrl(type: SetupModule.Type): String {
        return "$WIKI_BASE_URL/${type.helpPath}"
    }

    companion object {
        private const val WIKI_BASE_URL = "https://github.com/d4rken-org/butler/wiki"
    }

    data class State(
        val items: List<SetupItem> = emptyList(),
        val allRequiredComplete: Boolean = false,
    )

    @AssistedFactory
    interface Factory {
        fun create(options: SetupScreenOptions): SetupViewModel
    }
}

private val SetupModule.Type.priority: Int
    get() = when (this) {
        SetupModule.Type.STORAGE -> 1
        SetupModule.Type.NOTIFICATION -> 2
        SetupModule.Type.USAGE_STATS -> 3
        SetupModule.Type.ROOT -> 4
        SetupModule.Type.SHIZUKU -> 5
        SetupModule.Type.INVENTORY -> 6
    }
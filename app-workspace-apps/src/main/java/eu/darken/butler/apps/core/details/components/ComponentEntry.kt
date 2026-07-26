package eu.darken.butler.apps.core.details.components

import android.content.pm.PackageManager

enum class ComponentKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

/**
 * Effective enablement of a component.
 *
 * [UNRESOLVED] is the state every entry starts in: the cheap manifest query cannot tell whether a
 * component was disabled at runtime, so nothing is claimed until the enrichment pass has run.
 */
enum class ComponentEnabledState { UNRESOLVED, ENABLED, DISABLED }

data class ComponentEntry(
    val kind: ComponentKind,
    val packageName: String,
    val className: String,
    val isExported: Boolean,
    // Manifest baseline (ComponentInfo.isEnabled), kept so the enrichment pass can resolve
    // COMPONENT_ENABLED_STATE_DEFAULT without re-reading the package.
    val manifestEnabled: Boolean = true,
    val enabledState: ComponentEnabledState = ComponentEnabledState.UNRESOLVED,
    val permission: String? = null,
    val writePermission: String? = null,
    val authority: String? = null,
    val processName: String? = null,
    val launchMode: Int? = null,
) {
    val simpleName: String get() = className.substringAfterLast('.')
    val key: String get() = "${kind.name}:$className"
}

data class ComponentsData(
    val activities: List<ComponentEntry> = emptyList(),
    val services: List<ComponentEntry> = emptyList(),
    val receivers: List<ComponentEntry> = emptyList(),
    val providers: List<ComponentEntry> = emptyList(),
) {
    val total: Int get() = activities.size + services.size + receivers.size + providers.size

    val all: List<ComponentEntry> get() = activities + services + receivers + providers

    fun withEnabledStates(states: Map<String, Boolean>): ComponentsData = ComponentsData(
        activities = activities.map { it.applyEnabledState(states) },
        services = services.map { it.applyEnabledState(states) },
        receivers = receivers.map { it.applyEnabledState(states) },
        providers = providers.map { it.applyEnabledState(states) },
    )
}

private fun ComponentEntry.applyEnabledState(states: Map<String, Boolean>): ComponentEntry {
    val enabled = states[key] ?: return this
    return copy(
        enabledState = if (enabled) ComponentEnabledState.ENABLED else ComponentEnabledState.DISABLED,
    )
}

sealed interface ComponentsUiState {
    data object Loading : ComponentsUiState
    data class Ready(val data: ComponentsData) : ComponentsUiState
    data object Error : ComponentsUiState
}

/**
 * Keeps entries whose short or fully-qualified name contains [query].
 *
 * Deliberately does not trim: the caller passes an already-normalized query so filtering and
 * highlighting can never disagree about what counts as a match.
 */
fun ComponentsData.filter(query: String): ComponentsData {
    if (query.isBlank()) return this
    fun List<ComponentEntry>.matching(): List<ComponentEntry> = filter {
        it.simpleName.contains(query, ignoreCase = true) || it.className.contains(query, ignoreCase = true)
    }
    return ComponentsData(
        activities = activities.matching(),
        services = services.matching(),
        receivers = receivers.matching(),
        providers = providers.matching(),
    )
}

/**
 * Effective enablement from the runtime override, the application state and the manifest baseline.
 *
 * [appEnabled] matters because `getComponentEnabledSetting` reports only the *component* override:
 * a disabled application whose components all read `DEFAULT` would otherwise look enabled.
 */
internal fun resolveEnabled(
    componentSetting: Int,
    appEnabled: Boolean,
    manifestEnabled: Boolean,
): Boolean = appEnabled && when (componentSetting) {
    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
    else -> manifestEnabled
}

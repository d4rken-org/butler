package eu.darken.butler.explorer.core.sorting.rules

import eu.darken.butler.explorer.core.SortSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A tab's private sort overrides. Survives process death with the session, dies with the tab.
 *
 * The JSON shape below and the slot key in [ExplorerTabSortStore] are wire contract - the workspace
 * layer stores this payload opaquely, so nothing else would notice a format break.
 */
@Serializable
data class TabSortOverrides(
    /** Replaces the global default for this tab. */
    @SerialName("default") val default: SortSettings? = null,
    /** pathKey -> rule. */
    @SerialName("rules") val rules: Map<String, TabSortRule> = emptyMap(),
) {
    val isEmpty: Boolean get() = default == null && rules.isEmpty()
}

@Serializable
data class TabSortRule(
    /** Null means "use the default here", the tab-local twin of a persistent marker. */
    @SerialName("settings") val settings: SortSettings?,
    @SerialName("subtree") val subtree: Boolean = false,
    /** Serialized APath, so an inherited tab rule can name its folder in the sheet's notice. */
    @SerialName("path") val path: String,
)

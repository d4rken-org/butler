package eu.darken.butler.explorer.core.sorting.rules

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.SortSettings

/** Which layer a rule came from. The tab layer only wins ties at the same key. */
enum class SortRuleLayer {
    TAB,
    SAVED,
    ;
}

/** One candidate rule, from either layer. [settings] null means "use the default here". */
data class SortRuleCandidate(
    val settings: SortSettings?,
    val subtree: Boolean,
    /** Folder that owns the rule, so the sheet's notices can name it. Null when it is unreadable. */
    val path: APath<*>?,
)

/**
 * The sort a folder actually gets, plus where it came from.
 *
 * The sheet derives its badge, notices, pre-selected scope and checkbox state from this one value,
 * so it can never disagree with what the sorter did.
 */
data class EffectiveSortResolution(
    val settings: SortSettings,
    /** Null when nothing matched and the tab/global default applies. */
    val winnerKey: String? = null,
    /** Index into the ancestor key list; 0 means the folder itself. */
    val winnerIndex: Int? = null,
    val winnerLayer: SortRuleLayer? = null,
    val winnerSubtree: Boolean = false,
    val winnerPath: APath<*>? = null,
    /** True when the folder itself carries a "use the default here" marker. */
    val ownsFollowDefault: Boolean = false,
    /** Nearest rule the winner hides, for the "this overrides …" notice. */
    val suppressedAncestorPath: APath<*>? = null,
)

/**
 * Resolves the layered sort for one folder.
 *
 * Selection is by index in [ancestorKeys], never by string length: specificity is compared first and
 * only then the layer, so a *nearer* saved rule beats a *farther* tab rule and the tab only wins at
 * the same key. A "use the default here" winner falls through to the tab default before the global
 * one - it suppresses rules at and above the folder, not the tab's own default.
 */
object EffectiveSortResolver {

    fun resolve(
        ancestorKeys: List<String>,
        tabRules: Map<String, SortRuleCandidate>,
        savedRules: Map<String, SortRuleCandidate>,
        tabDefault: SortSettings?,
        globalDefault: SortSettings,
    ): EffectiveSortResolution {
        val matches = ancestorKeys.mapIndexedNotNull { index, key ->
            val applicable = { rule: SortRuleCandidate -> index == 0 || rule.subtree }
            val tab = tabRules[key]?.takeIf(applicable)?.let { SortRuleLayer.TAB to it }
            val saved = savedRules[key]?.takeIf(applicable)?.let { SortRuleLayer.SAVED to it }
            val (layer, rule) = tab ?: saved ?: return@mapIndexedNotNull null
            Triple(index, layer, rule) to key
        }

        val (winner, winnerKey) = matches.firstOrNull()
            ?: return EffectiveSortResolution(settings = tabDefault ?: globalDefault)
        val (winnerIndex, winnerLayer, winnerRule) = winner

        return EffectiveSortResolution(
            settings = winnerRule.settings ?: tabDefault ?: globalDefault,
            winnerKey = winnerKey,
            winnerIndex = winnerIndex,
            winnerLayer = winnerLayer,
            winnerSubtree = winnerRule.subtree,
            winnerPath = winnerRule.path,
            ownsFollowDefault = winnerIndex == 0 && winnerRule.settings == null,
            suppressedAncestorPath = matches.getOrNull(1)?.first?.third?.path,
        )
    }
}

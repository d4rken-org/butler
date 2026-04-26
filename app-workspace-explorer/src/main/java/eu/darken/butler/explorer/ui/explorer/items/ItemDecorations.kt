package eu.darken.butler.explorer.ui.explorer.items

/**
 * Decorations to overlay on a row/grid item's leading icon.
 *
 * Add a field here when introducing a new uniformly-applied decoration; render it in
 * [LeadingIconSlot] and derive it for the relevant item types in
 * [ExplorerItemRenderer.decorationsFor]. The leaf row/grid composables forward this
 * value opaquely — adding a new field does not require editing them.
 */
data class ItemDecorations(
    val isFavorite: Boolean = false,
)

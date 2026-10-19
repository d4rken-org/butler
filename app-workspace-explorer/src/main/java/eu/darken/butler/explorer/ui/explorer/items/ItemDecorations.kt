package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.runtime.Immutable
import eu.darken.butler.workspace.core.appattribution.AppAttribution

/**
 * Decorations to overlay on a row/grid item's leading icon.
 *
 * Add a field here when introducing a new uniformly-applied decoration; render it in
 * [LeadingIconSlot] and derive it for the relevant item types in
 * [ExplorerItemRenderer.decorationsFor].
 *
 * Immutable so rows compare it by value: a fresh instance is built for every item on every pass.
 */
@Immutable
data class ItemDecorations(
    val isFavorite: Boolean = false,
    /** The app a package-named folder belongs to, e.g. `Android/data/com.example`. */
    val appAttribution: AppAttribution? = null,
)

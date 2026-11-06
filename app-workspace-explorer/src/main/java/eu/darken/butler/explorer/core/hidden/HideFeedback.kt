package eu.darken.butler.explorer.core.hidden

import eu.darken.butler.common.ca.CaString

/**
 * UI-state record for a hide the user should still get feedback on.
 *
 * @param id Monotonically-increasing id, so a superseded hide's timeout cannot clear newer feedback.
 * @param displayName Name of the single (or first) newly hidden entry.
 * @param count Number of newly hidden entries; picks the singular or plural message.
 * @param showHiddenOn The tab showed hidden entries when the hide happened, so the entries stay
 *        visible (dimmed) and the bar offers to stop showing them.
 */
data class HideFeedback(
    val id: Long,
    val displayName: CaString,
    val count: Int,
    val showHiddenOn: Boolean,
)

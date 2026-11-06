package eu.darken.butler.explorer.core.hidden

/** One snapshot of the hide list; [keys] is derived from [entries], never cached on its own. */
data class HiddenPaths(
    val entries: List<HiddenEntry>,
) {
    val keys: Set<String> = entries.mapTo(mutableSetOf()) { it.key }

    companion object {
        val EMPTY = HiddenPaths(emptyList())
    }
}

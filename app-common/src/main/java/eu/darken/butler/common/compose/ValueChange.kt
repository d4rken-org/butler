package eu.darken.butler.common.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

private class ValueHolder<T>(var value: T)

/**
 * Runs [block] when [value] actually changes, never on the initial composition.
 *
 * `LaunchedEffect(value)` also fires on first composition, which for scroll-to-top style effects
 * means they re-run on every recomposition of a fresh composition (pane move, rotation) and undo
 * whatever position the list came up with.
 */
@Composable
fun <T> OnValueChange(value: T, block: suspend (previous: T, current: T) -> Unit) {
    // Plain holder rather than snapshot state: updating it must not trigger a recomposition.
    val holder = remember { ValueHolder(value) }
    LaunchedEffect(value) {
        val previous = holder.value
        if (previous == value) return@LaunchedEffect
        holder.value = value
        block(previous, value)
    }
}

package eu.darken.butler.common.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [StateFlow] starts at its current value, so single-frame renderers such as screenshot tests and
 * IDE previews see real state on the first composition. Other flows start at [initial].
 */
@Composable
fun <T> Flow<T>.collectAsStateCurrentOr(initial: T): State<T> =
    if (this is StateFlow<T>) collectAsState() else collectAsState(initial)

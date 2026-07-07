package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.explorer.ui.explorer.util.FocusNavigationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Keyboard focus for the item list, delegating the wrap-around math to [FocusNavigationState].
 *
 * Focus stays ViewModel-owned rather than UI-local (`rememberSaveable`): the index is cleared
 * reactively when favorites reorder the listing and clamped when the item count changes, and it
 * feeds both scroll-into-view and keyboard shortcut handling on the page.
 */
class ExplorerFocusController {

    private val stateFlow = MutableStateFlow(FocusNavigationState())

    val focusedIndex: Flow<Int?> = stateFlow.map { it.focusedIndex }.distinctUntilChanged()

    fun updateItemCount(count: Int) = stateFlow.update { it.updateItemCount(count) }

    fun moveUp() = stateFlow.update { it.moveFocusUp() }

    fun moveDown() = stateFlow.update { it.moveFocusDown() }

    fun moveLeft(gridColumns: Int) = stateFlow.update { it.moveFocusLeft(gridColumns) }

    fun moveRight(gridColumns: Int) = stateFlow.update { it.moveFocusRight(gridColumns) }

    fun moveToFirst() = stateFlow.update { it.moveFocusToFirst() }

    fun moveToLast() = stateFlow.update { it.moveFocusToLast() }

    fun clear() = stateFlow.update { it.clearFocus() }
}

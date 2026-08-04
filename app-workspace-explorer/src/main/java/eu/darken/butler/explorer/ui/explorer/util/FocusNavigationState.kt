package eu.darken.butler.explorer.ui.explorer.util

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Immutable state holder for keyboard focus navigation.
 * Handles focus movement through list/grid items with wrap-around behavior.
 */
data class FocusNavigationState(
    val focusedIndex: Int? = null,
    val itemCount: Int = 0,
) {
    companion object {
        val Saver: Saver<FocusNavigationState, *> = listSaver(
            save = { listOf(it.focusedIndex, it.itemCount) },
            restore = { FocusNavigationState(focusedIndex = it[0], itemCount = it[1] as Int) },
        )
    }
    val hasFocus: Boolean get() = focusedIndex != null

    fun moveFocusUp(): FocusNavigationState {
        if (itemCount == 0) return this
        val newIndex = when (focusedIndex) {
            null -> itemCount - 1
            0 -> itemCount - 1 // Wrap around
            else -> focusedIndex - 1
        }
        return copy(focusedIndex = newIndex)
    }

    fun moveFocusDown(): FocusNavigationState {
        if (itemCount == 0) return this
        val newIndex = when (focusedIndex) {
            null -> 0
            itemCount - 1 -> 0 // Wrap around
            else -> focusedIndex + 1
        }
        return copy(focusedIndex = newIndex)
    }

    fun moveFocusLeft(gridColumns: Int): FocusNavigationState {
        if (itemCount == 0) return this
        val newIndex = when {
            focusedIndex == null -> itemCount - 1
            focusedIndex < gridColumns -> itemCount - 1 // Wrap around
            else -> focusedIndex - gridColumns
        }
        return copy(focusedIndex = newIndex)
    }

    fun moveFocusRight(gridColumns: Int): FocusNavigationState {
        if (itemCount == 0) return this
        val newIndex = when {
            focusedIndex == null -> 0
            focusedIndex >= itemCount - gridColumns -> 0 // Wrap around
            else -> minOf(focusedIndex + gridColumns, itemCount - 1)
        }
        return copy(focusedIndex = newIndex)
    }

    fun moveFocusToFirst(): FocusNavigationState {
        if (itemCount == 0) return this
        return copy(focusedIndex = 0)
    }

    fun moveFocusToLast(): FocusNavigationState {
        if (itemCount == 0) return this
        return copy(focusedIndex = itemCount - 1)
    }

    fun clearFocus(): FocusNavigationState = copy(focusedIndex = null)

    fun updateItemCount(newCount: Int): FocusNavigationState {
        val newIndex = when {
            focusedIndex == null -> null
            newCount == 0 -> null
            focusedIndex >= newCount -> newCount - 1
            else -> focusedIndex
        }
        return copy(focusedIndex = newIndex, itemCount = newCount)
    }
}

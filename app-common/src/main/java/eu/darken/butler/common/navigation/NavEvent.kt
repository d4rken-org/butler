package eu.darken.butler.common.navigation

sealed interface NavEvent {
    data class GoTo(
        val destination: NavigationDestination,
        val popUpTo: NavigationDestination? = null,
        val inclusive: Boolean = false,
    ) : NavEvent

    /**
     * Navigate to [destination] without ever duplicating or re-creating it: no-op if it is already
     * the current top, pop back to it if it exists deeper in the stack, otherwise push it.
     */
    data class GoToSingleTop(
        val destination: NavigationDestination,
    ) : NavEvent

    data object Up : NavEvent

    data object Finish : NavEvent
}

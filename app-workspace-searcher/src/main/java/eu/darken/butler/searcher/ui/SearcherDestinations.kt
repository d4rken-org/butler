package eu.darken.butler.searcher.ui

import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable


@Serializable
data object DestinationSearcher : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Workspaces.searcher(): NavigationDestination = DestinationSearcher

@Serializable
data object DestinationSearcherSettings : NavigationDestination

@Suppress("UnusedReceiverParameter")
fun Nav.Settings.searcher(): NavigationDestination = DestinationSearcherSettings
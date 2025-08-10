package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.engine.ExplorerLocation

sealed class NavigationRequest {
    data class ToPath(val path: APath, val addToHistory: Boolean = true) : NavigationRequest()
    data class ToLocation(val location: ExplorerLocation, val addToHistory: Boolean = true) : NavigationRequest()
    data class ToBreadcrumb(val target: ExplorerLocation.Breadcrumb.Target) : NavigationRequest()
    object Back : NavigationRequest()
    object Forward : NavigationRequest()
    object Refresh : NavigationRequest()
    object Cancel : NavigationRequest()
}
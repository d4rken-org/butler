package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.explorer.core.engine.ExplorerItem

val ExplorerItem.itemId: String
    get() = when(this) {
        is ExplorerItem.PathItem -> this.lookup.path
        is ExplorerItem.Shortcut -> this.shortcutId
    }
package eu.darken.butler.explorer.ui.explorer

sealed class ExplorerScrollIntent {
    data object Top : ExplorerScrollIntent()
    data class Restore(val index: Int, val offset: Int) : ExplorerScrollIntent()
    data class ToIndex(val index: Int, val animate: Boolean = false) : ExplorerScrollIntent()
}

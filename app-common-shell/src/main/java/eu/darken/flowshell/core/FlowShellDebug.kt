package eu.darken.flowshell.core

import eu.darken.butler.common.debug.Bugs

object FlowShellDebug {
    var isDebug: Boolean = Bugs.isTrace
    internal var tag = "BUTLER:FS"
}
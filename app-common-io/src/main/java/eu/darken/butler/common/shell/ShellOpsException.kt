package eu.darken.butler.common.shell

import eu.darken.butler.common.shell.ipc.ShellOpsCmd
import java.io.IOException

open class ShellOpsException @JvmOverloads constructor(
    message: String? = "Shell error.",
    cmd: ShellOpsCmd? = null,
    cause: Throwable? = null,
) : IOException("Error executing $cmd: $message", cause)
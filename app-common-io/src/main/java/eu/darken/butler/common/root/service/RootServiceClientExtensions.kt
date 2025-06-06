package eu.darken.butler.common.root.service

import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.sharedresource.runSessionAction


@Suppress("UNCHECKED_CAST")
suspend fun <R, T> RootServiceClient.runModuleAction(
    moduleClass: Class<out R>,
    action: suspend (R) -> T
): T = runSessionAction { session ->
    if (Bugs.isTrace) {
        log(RootServiceClient.TAG, VERBOSE) { "runModuleAction(moduleClass=$moduleClass, action=$action)" }
    }
    val module = session.clientModules.single { moduleClass.isInstance(it) } as R
    return@runSessionAction action(module)
}
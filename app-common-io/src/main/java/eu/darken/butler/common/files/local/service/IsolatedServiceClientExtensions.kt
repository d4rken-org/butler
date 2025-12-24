package eu.darken.butler.common.files.local.service

import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.sharedresource.runSessionAction

@Suppress("UNCHECKED_CAST")
suspend fun <R, T> IsolatedServiceClient.runModuleAction(
    moduleClass: Class<out R>,
    action: suspend (R) -> T,
): T = runSessionAction { session ->
    if (Bugs.isTrace) {
        log(IsolatedServiceClient.TAG, VERBOSE) { "runModuleAction(moduleClass=$moduleClass)" }
    }
    val module = session.clientModules.single { moduleClass.isInstance(it) } as R
    return@runSessionAction action(module)
}

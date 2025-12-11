package eu.darken.butler.common

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.setup.core.SetupModule

open class ElevatedAccessUnavailableException(
    message: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext): LocalizedError {
        val navController = context.navController
        return LocalizedError(
            throwable = this,
            label = R.string.general_error_label.toCaString(),
            description = R.string.general_error_elevated_access_required.toCaString(),
            fixActionLabel = navController?.let { R.string.general_open_setup_action.toCaString() },
            fixAction = navController?.let { nav ->
                {
                    nav.goTo(
                        Nav.Main.destSetup(
                            typeFilter = setOf(SetupModule.Type.ROOT, SetupModule.Type.SHIZUKU),
                            showCompleted = true,
                        )
                    )
                }
            },
        )
    }
}

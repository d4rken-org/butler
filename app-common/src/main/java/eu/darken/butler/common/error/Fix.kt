package eu.darken.butler.common.error

import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.setup.core.SetupModule

sealed interface Fix {

    fun label(): CaString

    fun action(context: LocalizedErrorContext): (() -> Unit)?

    data object ConfigureRootOrShizuku : Fix {
        override fun label(): CaString = R.string.general_open_setup_action.toCaString()
        override fun action(context: LocalizedErrorContext): (() -> Unit)? =
            context.openSetup(setOf(SetupModule.Type.ROOT, SetupModule.Type.SHIZUKU))
    }

    data object GrantUsageStats : Fix {
        override fun label(): CaString = R.string.general_grant_usage_stats_action.toCaString()
        override fun action(context: LocalizedErrorContext): (() -> Unit)? =
            context.openSetup(setOf(SetupModule.Type.USAGE_STATS))
    }

    data object GrantStoragePermission : Fix {
        override fun label(): CaString = R.string.general_grant_storage_permission_action.toCaString()
        override fun action(context: LocalizedErrorContext): (() -> Unit)? =
            context.openSetup(setOf(SetupModule.Type.STORAGE))
    }

    data object GrantAllFilesAccess : Fix {
        override fun label(): CaString = R.string.general_grant_all_files_access_action.toCaString()
        override fun action(context: LocalizedErrorContext): (() -> Unit)? =
            context.openSetup(setOf(SetupModule.Type.STORAGE))
    }
}

private fun LocalizedErrorContext.openSetup(typeFilter: Set<SetupModule.Type>): (() -> Unit)? {
    val nav = navController ?: return null
    return {
        nav.goTo(
            Nav.Main.destSetup(
                typeFilter = typeFilter,
                showCompleted = true,
            )
        )
    }
}

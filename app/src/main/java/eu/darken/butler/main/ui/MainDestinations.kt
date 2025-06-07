package eu.darken.butler.main.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Suppress("JavaIoSerializableObjectMustHaveReadResolve")
sealed interface MainNav : NavKey, java.io.Serializable {

    @Serializable
    data object Home : MainNav

    @Serializable
    data object Onboarding : MainNav

    @Serializable
    data object Settings : MainNav {
        @Serializable
        data object General : MainNav
    }

}
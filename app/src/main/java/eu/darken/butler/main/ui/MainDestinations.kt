package eu.darken.butler.main.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface MainNav : NavKey, java.io.Serializable {

    @Serializable
    data class Home(
        val data: String = "1"
    ) : MainNav

    @Serializable
    data class Onboarding(
        val data: String = "2",
    ) : MainNav

    @Serializable
    data class Settings(
        val data: String = "3",
    ) : MainNav
}
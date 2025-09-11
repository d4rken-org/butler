package eu.darken.butler.setup.ui

import android.os.Parcelable
import eu.darken.butler.setup.core.SetupModule
import kotlinx.parcelize.Parcelize

@Parcelize
data class SetupScreenOptions(
    val typeFilter: Set<SetupModule.Type>? = null,
    val requiredTypes: Set<SetupModule.Type>? = null,
    val isOnboarding: Boolean = false,
    val showCompleted: Boolean = false,
) : Parcelable
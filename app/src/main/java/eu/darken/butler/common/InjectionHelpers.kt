package eu.darken.butler.common

import android.app.Service
import dagger.hilt.internal.GeneratedComponentManager
import eu.darken.butler.App

fun Service.isValidAndroidEntryPoint(): Boolean {
    return application is GeneratedComponentManager<*> || application is App
}
package eu.darken.butler.common.pkgs

import android.content.Intent

interface SDMaidTool {
    val installUrl: String
    suspend fun isInstalled(): Boolean
    fun launch(): Boolean
    fun getLaunchIntent(): Intent?
    fun openInstallPage()
}

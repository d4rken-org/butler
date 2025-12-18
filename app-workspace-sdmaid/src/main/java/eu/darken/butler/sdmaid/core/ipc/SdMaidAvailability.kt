package eu.darken.butler.sdmaid.core.ipc

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.pkgs.AKnownPkg
import eu.darken.butler.common.pkgs.SDMaidTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class SdMaidAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    val sdMaidTool: SDMaidTool,
) {

    data class State(
        val isInstalled: Boolean,
        val installedVersion: String?,
        val isServiceAvailable: Boolean,
    ) {
        val canConnect: Boolean get() = isInstalled && isServiceAvailable
    }

    val state: Flow<State> = flow {
        while (true) {
            emit(checkAvailability())
            delay(5.seconds)
        }
    }
        .distinctUntilChanged()
        .replayingShare(appScope)

    private suspend fun checkAvailability(): State {
        val isInstalled = sdMaidTool.isInstalled()

        if (!isInstalled) {
            log(TAG) { "SD Maid SE not installed" }
            return State(
                isInstalled = false,
                installedVersion = null,
                isServiceAvailable = false,
            )
        }

        val pm = context.packageManager
        return try {
            @Suppress("DEPRECATION")
            val packageInfo = pm.getPackageInfo(AKnownPkg.SDMaidSE.packageName, PackageManager.GET_SERVICES)
            val hasService = packageInfo.services?.any {
                it.name == SDMSE_SERVICE
            } ?: false

            log(TAG, INFO) { "SD Maid SE found: version=${packageInfo.versionName}, hasService=$hasService" }

            State(
                isInstalled = true,
                installedVersion = packageInfo.versionName,
                isServiceAvailable = hasService,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            log(TAG) { "SD Maid SE package info not found" }
            State(
                isInstalled = true,
                installedVersion = null,
                isServiceAvailable = false,
            )
        }
    }

    companion object {
        const val SDMSE_SERVICE = "eu.darken.sdmse.ipc.SdmService"
        private val TAG = logTag("SDMaid", "Availability")
    }
}

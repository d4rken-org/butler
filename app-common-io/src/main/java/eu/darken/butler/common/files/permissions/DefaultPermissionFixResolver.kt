package eu.darken.butler.common.files.permissions

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.error.Fix
import eu.darken.butler.common.error.PermissionFixResolver
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException.Reason
import eu.darken.butler.common.root.RootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultPermissionFixResolver @Inject constructor(
    rootManager: RootManager,
    adbManager: AdbManager,
    @AppScope appScope: CoroutineScope,
) : PermissionFixResolver {

    @Volatile
    private var rootAvailable: Boolean = false

    @Volatile
    private var adbAvailable: Boolean = false

    init {
        rootManager.useRoot
            .onEach { rootAvailable = it }
            .launchIn(appScope)

        adbManager.useAdb
            .onEach { adbAvailable = it }
            .launchIn(appScope)
    }

    override fun resolve(error: Throwable): Fix? {
        val pathError = error as? PathPermissionDeniedException ?: return null
        return when (pathError.reason) {
            Reason.READONLY_FILESYSTEM, Reason.NOT_PERMITTED -> null
            Reason.NO_MECHANISM, Reason.ACCESS_DENIED -> {
                if (!rootAvailable && !adbAvailable) Fix.ConfigureRootOrShizuku else null
            }
        }
    }
}

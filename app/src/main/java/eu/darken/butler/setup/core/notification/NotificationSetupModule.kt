package eu.darken.butler.setup.core.notification

import android.content.Context
import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.rngString
import eu.darken.butler.setup.core.SetupModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class NotificationSetupModule @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context,
) : SetupModule {

    private val refreshTrigger = MutableStateFlow(rngString)
    override val state: Flow<SetupModule.State> = refreshTrigger
        .mapLatest {
            val requiredPermission = getRequiredPermission()

            val missingPermission = requiredPermission.filter {
                val isGranted = it.isGranted(context)
                log(TAG) { "${it.permissionId} isGranted=$isGranted" }
                !isGranted
            }.toSet()

            @Suppress("USELESS_CAST")
            Result(
                missingPermission = missingPermission,
            ) as SetupModule.State
        }
        .onStart { emit(Loading()) }
        .replayingShare(appScope)

    private fun getRequiredPermission(): Set<Permission> = when {
        hasApiLevel(33) -> setOf(Permission.POST_NOTIFICATIONS)
        else -> emptySet()
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = rngString
    }

    fun getPermissionIntent(): Intent? {
        // POST_NOTIFICATIONS is a runtime permission, not a special permission
        // Return null here since it should be handled via permission launcher
        return null
    }

    fun getRuntimePermissions(): Set<String> {
        val requiredPermissions = getRequiredPermission()
        return requiredPermissions
            .filter { !it.isGranted(context) }
            .map { it.permissionId }
            .toSet()
    }

    data class Loading(
        override val startAt: Instant = Clock.System.now(),
    ) : SetupModule.State.Loading {
        override val type: SetupModule.Type = SetupModule.Type.NOTIFICATION
    }

    data class Result(
        val missingPermission: Set<Permission>,
    ) : SetupModule.State.Current {

        override val type: SetupModule.Type
            get() = SetupModule.Type.NOTIFICATION

        override val isComplete: Boolean = missingPermission.isEmpty()

    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: NotificationSetupModule): SetupModule
    }

    companion object {
        private val TAG = logTag("Setup", "Notification", "Module")
    }
}